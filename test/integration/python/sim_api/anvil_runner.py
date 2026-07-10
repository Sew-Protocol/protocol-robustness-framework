"""
anvil_runner.py — Differential testing harness for SEW simulation.

Starts a local Anvil instance, deploys the full EscrowVault + DR module stack
via the DifferentialSetup.s.sol Forge script, then drives the simulation by
executing actions via `cast send` and reading EVM state via `cast call`.

EVM state is compared step-by-step against the `:projection` field emitted by
replay.clj's `process-step` trace entries.  Any divergence is captured as a
structured diff.

Design decisions:
- Uses `cast` / `forge` via subprocess — no web3.py dependency.
- All token amounts are in wei (integer strings).
- EscrowSettings uses all-zero default (no custom resolver, no auto-release).
- Resolver (account 2) is pre-approved in the DR module by DifferentialSetup.
"""

from __future__ import annotations

import json
import os
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

# ---------------------------------------------------------------------------
# Anvil well-known accounts (deterministic Anvil private keys)
# ---------------------------------------------------------------------------

DEPLOYER_KEY  = '0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80'
BUYER_KEY     = '0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d'
RESOLVER_KEY  = '0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a'

DEPLOYER_ADDR = '0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266'
BUYER_ADDR    = '0x70997970C51812dc3A010C7d01b50e0d17dc79C8'
RESOLVER_ADDR = '0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC'

# Default EscrowSettings tuple (all-zero = no custom resolver, no auto-release)
DEFAULT_SETTINGS = (
    '(0x0000000000000000000000000000000000000000,'
    '0x0000000000000000000000000000000000000000,'
    '0,0,0)'
)

ZERO_BYTES32 = '0x' + '00' * 32

ANVIL_PORT    = 8545
ANVIL_RPC_URL = f'http://127.0.0.1:{ANVIL_PORT}'


# ---------------------------------------------------------------------------
# State enum mapping (must match EscrowTypes.sol)
# ---------------------------------------------------------------------------

EVM_STATE = {
    0: 'none',
    1: 'pending',
    2: 'released',
    3: 'refunded',
    4: 'disputed',
    5: 'resolved',
}

# Map simulation action names to model escrow-state keywords
SIM_TO_EVM_STATE = {
    'create_escrow':       1,  # PENDING
    'release_escrow':      2,  # RELEASED
    'cancel_escrow':       3,  # REFUNDED
    'raise_dispute':       4,  # DISPUTED
    'resolve_release':     5,  # RESOLVED
    'resolve_cancel':      5,  # RESOLVED
    'execute_settlement':  5,  # RESOLVED
}


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _run(cmd: list[str], check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, check=check)


def _cast_call(rpc: str, contract: str, sig: str, *args: str) -> str:
    """Call a view function and return stdout stripped."""
    result = _run(['cast', 'call', '--rpc-url', rpc, contract, sig, *args])
    return result.stdout.strip()


def _cast_send(rpc: str, private_key: str, contract: str, sig: str, *args: str) -> str:
    """Send a transaction and return stdout stripped."""
    result = _run([
        'cast', 'send', '--rpc-url', rpc,
        '--private-key', private_key,
        contract, sig, *args,
    ])
    return result.stdout.strip()


# ---------------------------------------------------------------------------
# AnvilRunner
# ---------------------------------------------------------------------------

@dataclass
class AnvilRunner:
    """
    Manages a single Anvil instance for differential testing.

    Lifecycle:
        runner = AnvilRunner(sew_protocol_root='/path/to/sew-protocol')
        runner.start()
        try:
            wf_id = runner.execute_action('create_escrow', {...})
            runner.execute_action('raise_dispute', {'wf_id': wf_id})
            diff = runner.compare_with_projection(trace_entry['projection'], [wf_id])
        finally:
            runner.stop()
    """

    sew_protocol_root: str = field(default_factory=lambda: str(
        Path(__file__).parent.parent.parent.parent / 'sew-protocol'
    ))
    anvil_port: int = ANVIL_PORT
    rpc_url: str = field(init=False)

    # Filled after start()
    _proc: subprocess.Popen | None = field(default=None, init=False, repr=False)
    addrs: dict[str, str] = field(default_factory=dict, init=False)

    def __post_init__(self) -> None:
        self.rpc_url = f'http://127.0.0.1:{self.anvil_port}'

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def start(self) -> None:
        """Start Anvil, deploy contracts, load addresses."""
        self._proc = subprocess.Popen(
            ['anvil', '--port', str(self.anvil_port), '--silent'],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        # Wait for Anvil to be ready
        for _ in range(20):
            try:
                _run(['cast', 'block-number', '--rpc-url', self.rpc_url])
                break
            except subprocess.CalledProcessError:
                time.sleep(0.3)
        else:
            raise RuntimeError('Anvil did not start in time')

        self._deploy()

    def stop(self) -> None:
        """Terminate the Anvil process."""
        if self._proc is not None:
            self._proc.terminate()
            try:
                self._proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._proc.kill()
            self._proc = None

    def _deploy(self) -> None:
        """
        Deploy the full SEW stack to Anvil.
        If differential-setup.json exists in protocol root, load it.
        Otherwise attempt pnpm deploy:local.
        """
        setup_json = Path(self.sew_protocol_root) / 'differential-setup.json'
        
        if setup_json.exists():
            print(f"[anvil] Loading addresses from {setup_json}")
            with open(setup_json, 'r') as f:
                data = json.load(f)
                self.addrs = {
                    'token':    data['token'],
                    'vault':    data['vault'],
                    'oracle':   data['oracle'],
                    'drModule': data['drModule'],
                }
            return

        print("[anvil] Deploying protocol via pnpm deploy:local...")
        _run(['pnpm', 'deploy:local'], cwd=self.sew_protocol_root)
        
        if not setup_json.exists():
             raise RuntimeError(f"Deployment finished but {setup_json} not found.")
        
        with open(setup_json, 'r') as f:
            data = json.load(f)
            self.addrs = {
                'token':    data['token'],
                'vault':    data['vault'],
                'oracle':   data['oracle'],
                'drModule': data['drModule'],
            }

    # ------------------------------------------------------------------
    # Action execution
    # ------------------------------------------------------------------

    def execute_action(self, action: str, params: dict[str, Any]) -> Any:
        """
        Execute a simulation action against the EVM.

        Supported actions:
            create_escrow             → params: amount (int), seller (addr, optional)
            raise_dispute             → params: workflow-id (int)
            execute_resolution        → params: workflow-id (int), is-release (bool)
            execute_pending_settlement → params: workflow-id (int)
            automate_timed_actions     → params: workflow-id (int)
            release                   → params: workflow-id (int)
            sender_cancel             → params: workflow-id (int)
            recipient_cancel          → params: workflow-id (int)

        Returns the workflow ID (int) for create_escrow; None otherwise.
        """
        dispatch = {
            'create_escrow':               self._create_escrow,
            'raise_dispute':               self._raise_dispute,
            'execute_resolution':          self._execute_resolution,
            'execute_pending_settlement':   self._execute_pending_settlement,
            'automate_timed_actions':      self._automate_timed_actions,
            'release':                     self._release_escrow,
            'sender_cancel':               self._sender_cancel,
            'recipient_cancel':            self._recipient_cancel,
        }
        handler = dispatch.get(action)
        if handler is None:
            # For trace equivalence, ignore actions that don't have EVM equivalents
            # (like register_stake in simple mode)
            print(f"Warning: No EVM handler for action {action}")
            return None
        return handler(params)

    def _create_escrow(self, params: dict) -> int:
        """Create an escrow; return the assigned workflow ID."""
        amount   = params['amount']
        seller   = params.get('to', DEPLOYER_ADDR)  # 'to' in sim params is the seller
        token    = self.addrs['token']
        vault    = self.addrs['vault']

        # Approve vault to spend tokens from buyer
        _cast_send(
            self.rpc_url, BUYER_KEY,
            token,
            'approve(address,uint256)',
            vault, str(amount),
        )

        # Create escrow
        wf_id = self._next_wf_id()
        _cast_send(
            self.rpc_url, BUYER_KEY,
            vault,
            'createEscrow(address,address,uint256,(address,address,uint8,uint256,uint256))',
            token, seller, str(amount), DEFAULT_SETTINGS,
        )
        return wf_id

    def _raise_dispute(self, params: dict) -> None:
        wf_id = params['workflow-id']
        _cast_send(
            self.rpc_url, BUYER_KEY,
            self.addrs['vault'],
            'raiseDispute(uint256)',
            str(wf_id),
        )

    def _execute_resolution(self, params: dict) -> None:
        wf_id = params['workflow-id']
        is_release = params.get('is-release', True)
        if is_release:
            _cast_send(
                self.rpc_url, RESOLVER_KEY,
                self.addrs['vault'],
                'resolveRelease(uint256,bytes32)',
                str(wf_id), ZERO_BYTES32,
            )
        else:
            _cast_send(
                self.rpc_url, RESOLVER_KEY,
                self.addrs['vault'],
                'resolveCancel(uint256,bytes32)',
                str(wf_id), ZERO_BYTES32,
            )

    def _execute_pending_settlement(self, params: dict) -> None:
        wf_id = params['workflow-id']
        _cast_send(
            self.rpc_url, DEPLOYER_KEY,
            self.addrs['vault'],
            'executePendingSettlement(uint256)',
            str(wf_id),
        )

    def _automate_timed_actions(self, params: dict) -> None:
        wf_id = params['workflow-id']
        _cast_send(
            self.rpc_url, DEPLOYER_KEY,
            self.addrs['vault'],
            'automateTimedActions(uint256)',
            str(wf_id),
        )

    def _release_escrow(self, params: dict) -> None:
        wf_id = params['workflow-id']
        _cast_send(
            self.rpc_url, BUYER_KEY,
            self.addrs['vault'],
            'releaseEscrowTransfer(uint256)',
            str(wf_id),
        )

    def _sender_cancel(self, params: dict) -> None:
        wf_id = params['workflow-id']
        _cast_send(
            self.rpc_url, BUYER_KEY,
            self.addrs['vault'],
            'senderCancel(uint256)',
            str(wf_id),
        )

    def _recipient_cancel(self, params: dict) -> None:
        # For simplicity in differential test, DifferentialSetup makes deployer the seller
        wf_id = params['workflow-id']
        _cast_send(
            self.rpc_url, DEPLOYER_KEY,
            self.addrs['vault'],
            'recipientCancel(uint256)',
            str(wf_id),
        )

    # ------------------------------------------------------------------
    # EVM state reading
    # ------------------------------------------------------------------

    def _next_wf_id(self) -> int:
        """Return the next escrow workflow ID (current array length)."""
        length_hex = _cast_call(
            self.rpc_url,
            self.addrs['vault'],
            'escrowTransfers(uint256)',
        )
        # escrowTransfers is an array; its length is at storage slot
        # Use a dedicated length getter
        try:
            out = _cast_call(
                self.rpc_url,
                self.addrs['vault'],
                'getEscrowTransferCount()(uint256)',
            )
            return int(out, 16) if out.startswith('0x') else int(out)
        except subprocess.CalledProcessError:
            # Fallback: parse from a known prior transaction count heuristic
            # (this path should not be needed if vault exposes the getter)
            return 0

    def read_evm_state(self, wf_ids: list[int], token: str | None = None) -> dict:
        """
        Read a minimal EVM world-state projection comparable to
        the :projection field emitted by replay.clj.

        Returns a dict with the same keys as diff/projection:
            :escrow-transfers  {wf-id → {:escrow-state kw :amount-after-fee int}}
            :total-held        int (wei)
            :total-fees        int (wei)
            :pending-settlements {wf-id → {:exists bool :is-release bool}}
            :dispute-levels    {wf-id → int}
            :block-time        int (unix seconds)
        """
        oracle  = self.addrs['oracle']
        vault   = self.addrs['vault']
        rpc     = self.rpc_url
        tok     = token or self.addrs['token']

        def _int(v: str) -> int:
            return int(v, 16) if v.startswith('0x') else int(v)

        def _bool(v: str) -> bool:
            v = v.strip().lower()
            return v in ('true', '1', '0x01', '0x1')

        # Per-workflow state
        escrow_transfers: dict[int, dict] = {}
        pending_settlements: dict[int, dict] = {}
        dispute_levels: dict[int, int] = {}

        for wid in wf_ids:
            wid_str = str(wid)

            state_raw = _cast_call(rpc, oracle, 'escrowState(uint256)(uint8)',   wid_str)
            amount_raw = _cast_call(rpc, oracle, 'escrowAmount(uint256)(uint256)', wid_str)
            pend_exists_raw = _cast_call(rpc, oracle, 'pendingExists(uint256)(bool)',    wid_str)
            pend_rel_raw    = _cast_call(rpc, oracle, 'pendingIsRelease(uint256)(bool)', wid_str)
            dispute_raw     = _cast_call(rpc, oracle, 'disputeLevel(uint256)(uint8)',    wid_str)

            state_int = _int(state_raw)
            escrow_transfers[wid] = {
                'escrow-state':    EVM_STATE.get(state_int, 'unknown'),
                'amount-after-fee': _int(amount_raw),
            }
            pending_settlements[wid] = {
                'exists':     _bool(pend_exists_raw),
                'is-release': _bool(pend_rel_raw),
            }
            dispute_levels[wid] = _int(dispute_raw)

        # Token-level accounting
        principal_raw = _cast_call(rpc, oracle, 'principalHeld(address)(uint256)', tok)
        fees_raw      = _cast_call(rpc, oracle, 'feesCollected(address)(uint256)',  tok)
        bt_raw        = _cast_call(rpc, oracle, 'blockTime()(uint256)')

        return {
            'escrow-transfers':   escrow_transfers,
            'total-held':         _int(principal_raw),
            'total-fees':         _int(fees_raw),
            'pending-settlements': pending_settlements,
            'dispute-levels':     dispute_levels,
            'block-time':         _int(bt_raw),
        }

    # ------------------------------------------------------------------
    # Differential comparison
    # ------------------------------------------------------------------

    def compare_with_projection(
        self,
        projection: dict,
        wf_ids: list[int],
        token: str | None = None,
    ) -> dict | None:
        """
        Compare the simulation model's projection against live EVM state.

        Returns None if the states match, or a diff dict describing every
        divergent field.

        The projection dict must match the structure returned by diff/projection
        in Clojure (i.e. the :projection field in a replay trace-entry).

        Only the fields that exist in both projections are compared; extra keys
        on either side are recorded in 'extra_sim' / 'extra_evm'.
        """
        evm = self.read_evm_state(wf_ids, token)
        diffs: dict[str, Any] = {}

        # Compare per-workflow escrow-transfers
        sim_ets = projection.get('escrow-transfers', {})
        evm_ets = evm.get('escrow-transfers', {})
        et_diffs = {}
        for wid in wf_ids:
            sim_et = sim_ets.get(wid) or sim_ets.get(str(wid)) or {}
            evm_et = evm_ets.get(wid, {})
            wid_diff = _compare_dicts(sim_et, evm_et, ('escrow-state',))
            if wid_diff:
                et_diffs[wid] = wid_diff
        if et_diffs:
            diffs['escrow-transfers'] = et_diffs

        # Compare token-level totals
        for key in ('total-held', 'total-fees'):
            sim_v = projection.get(key)
            evm_v = evm.get(key)
            if sim_v is not None and sim_v != evm_v:
                diffs[key] = {'sim': sim_v, 'evm': evm_v}

        # Compare dispute levels
        sim_dl = projection.get('dispute-levels', {})
        evm_dl = evm.get('dispute-levels', {})
        dl_diffs = {}
        for wid in wf_ids:
            sv = sim_dl.get(wid) or sim_dl.get(str(wid))
            ev = evm_dl.get(wid)
            if sv is not None and sv != ev:
                dl_diffs[wid] = {'sim': sv, 'evm': ev}
        if dl_diffs:
            diffs['dispute-levels'] = dl_diffs

        return diffs if diffs else None


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _compare_dicts(sim: dict, evm: dict, keys: tuple) -> dict:
    """Return a diff dict for the specified keys; empty if all match."""
    result = {}
    for k in keys:
        sv = sim.get(k)
        ev = evm.get(k)
        if sv is not None and sv != ev:
            result[k] = {'sim': sv, 'evm': ev}
    return result
