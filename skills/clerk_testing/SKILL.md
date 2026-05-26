# Skill: Check Clojure/Clerk Notebooks

## Purpose

Validate Clojure/Clerk notebooks before review or publishing, with special focus on:
- unmatched parentheses/brackets/braces
- namespace/load errors
- broken Clerk render output
- missing or empty HTML sections
- obvious runtime exceptions embedded in rendered notebook HTML

## When to use

Use this skill whenever:
- a notebook file has been edited
- Clerk output looks blank or partially rendered
- CI passes but the notebook UI looks suspicious
- there are reader errors such as `EOF while reading`, `Unmatched delimiter`, or `Unable to resolve symbol`

## Recommended validation order

### 1. Static reader check

Run a Clojure reader pass over notebook files before starting Clerk.

```bash
clojure -M -e '
(require (quote [clojure.java.io :as io]))
(doseq [f *command-line-args*]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader f))]
      (loop []
        (let [form (read {:eof ::eof} r)]
          (when-not (= form ::eof)
            (recur)))))
    (println "OK" f)
    (catch Throwable t
      (binding [*out* *err*]
        (println "FAIL" f)
        (println (.getMessage t)))
      (System/exit 1))))'
notebooks/**/*.clj

Expected result:

OK notebooks/report.clj
OK notebooks/workbench.clj

Failure examples to fix immediately:

EOF while reading
Unmatched delimiter: )
Map literal must contain an even number of forms
2. Namespace load check

After reader success, require the notebook namespace.

clojure -M -e "(require 'notebooks.report)"

For multiple notebooks:

for f in notebooks/*.clj; do
  ns=$(grep -m1 '^ *(ns ' "$f" | sed -E 's/^ *\(ns +([^ ]+).*/\1/')
  echo "Checking $ns"
  clojure -M -e "(require '$ns)" || exit 1
done

This catches:

missing requires
renamed vars
unresolved symbols
macro expansion errors
bad top-level forms
3. Clerk build/render check

Run the Clerk build command used by the project.

Examples:

bb clerk:build

or:

clojure -M:nextjournal/clerk build notebooks/report.clj

Fail the check if output contains:

Exception
CompilerException
Syntax error
Execution error
Unable to resolve symbol
EOF while reading
Unmatched delimiter
4. HTML smoke check with curl

If Clerk serves notebooks locally:

bb clerk:serve

Then in another shell:

curl -fsS http://localhost:7777/ > /tmp/notebook.html

Check for obvious failures:

grep -Ei "Exception|CompilerException|Execution error|Unable to resolve|Unmatched delimiter|EOF while reading" /tmp/notebook.html && exit 1
grep -Ei "<html|<body" /tmp/notebook.html >/dev/null || exit 1

Also check notebook-specific pages if applicable:

curl -fsS http://localhost:7777/notebooks/report > /tmp/report.html
5. Optional Playwright check

Use Playwright only after static and build checks pass.

import { test, expect } from "@playwright/test";

test("notebook renders without visible errors", async ({ page }) => {
  await page.goto("http://localhost:7777/notebooks/report");

  await expect(page.locator("body")).toBeVisible();

  const body = await page.locator("body").innerText();

  expect(body).not.toMatch(/Exception|CompilerException|Execution error/i);
  expect(body).not.toMatch(/Unable to resolve symbol|Unmatched delimiter|EOF while reading/i);

  await expect(page.locator("text=Sew Protocol")).toBeVisible();
});

Run:

npx playwright test
Preferred CI gate

Use a layered gate:

bb notebook:reader-check
bb notebook:require-check
bb clerk:build
bb notebook:html-smoke

Only run Playwright in the heavier validation job:

bb notebook:e2e
Suggested Babashka tasks
{:tasks
 {notebook:reader-check
  {:doc "Check notebooks can be read by the Clojure reader"
   :task (shell "clojure -M:scripts scripts/check_notebook_reader.clj notebooks")}

  notebook:require-check
  {:doc "Require notebook namespaces"
   :task (shell "clojure -M:scripts scripts/check_notebook_requires.clj notebooks")}

  notebook:html-smoke
  {:doc "Curl rendered Clerk HTML and check for obvious failures"
   :task (shell "bash scripts/check_notebook_html.sh")}

  notebook:check
  {:doc "Full fast notebook validation"
   :depends [notebook:reader-check
             notebook:require-check
             clerk:build
             notebook:html-smoke]}}}
Agent instruction

When checking notebooks:

Always run a reader check first.
Do not debug Clerk rendering until the reader check passes.
If a reader error occurs, identify the file and nearest likely form.
Prefer fixing structural syntax before changing logic.
After any fix, rerun:
reader check
namespace require check
Clerk build
Only use Playwright for UI-level validation once Clojure loading succeeds.
Common failure patterns
Unclosed form
(defn panel []
  [:div
   [:h2 "Title"]
   [:p "Body"]

Fix:

(defn panel []
  [:div
   [:h2 "Title"]
   [:p "Body"]])
Extra closing paren
(def cards
  (map render-card data)))

Fix:

(def cards
  (map render-card data))
Broken Hiccup vector
[:div
 [:h2 "Title")
 [:p "Body"]]

Fix:

[:div
 [:h2 "Title"]
 [:p "Body"]]
Output format for agents

Return:

## Notebook Check Result

Status: PASS / FAIL

### Checks
- Reader check:
- Namespace require:
- Clerk build:
- HTML smoke:
- Playwright:

### Failures
- File:
- Error:
- Likely cause:
- Suggested fix:

### Next command
```bash
...
