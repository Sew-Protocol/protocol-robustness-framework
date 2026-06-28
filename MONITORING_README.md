# PRF Monitoring System

## Quick Start

### 1. Start the Monitoring System

```bash
# Start monitoring from command line
clojure -M -m start-monitoring

# Or start it programmatically
(require '[resolver-sim.monitoring :as monitoring])
(monitoring/init-monitoring!)
```

### 2. Access the Dashboard

- **Web Dashboard**: [http://localhost:8090/monitoring](http://localhost:8090/monitoring)
- **JSON API**: [http://localhost:8090/api/monitoring](http://localhost:8090/api/monitoring)
- **JMX Console**: Connect to port 1099

### 3. Monitor Your Code

```clojure
;; Monitor a thread pool
(require '[resolver-sim.monitoring :as monitoring])

(let [executor (java.util.concurrent.Executors/newFixedThreadPool 4)]
  (monitoring/monitor-thread-pool "scenario-executor" executor)
  ;; Use your executor...
  )

;; Add custom metrics
(monitoring/increment-metric [:scenarios :completed])
(monitoring/timing-metric 150 [:scenario :processing-time])
(monitoring/gauge-metric 42 [:system :active-connections])
```

## Architecture Overview

```
┌───────────────────────────────────────────────────┐
│                 Monitoring System                   │
├─────────────────┬─────────────────┬─────────────────┤
│   JMX Server    │  Metrics System │ Web Dashboard   │
│  (Port 1099)    │  (Atomic ops)   │ (Port 8090)     │
└─────────┬────────┴─────────┬────────┴─────────┬───────┘
          │                   │                   │
          ▼                   ▼                   ▼
┌─────────────────┐ ┌─────────────┐ ┌─────────────────┐
│  JConsole/      │ │  Your Code   │ │  Web Browser    │
│  VisualVM       │ │  (Instrumented)│ │  (Dashboard)     │
└─────────────────┘ └─────────────┘ └─────────────────┘
```

## Components

### 1. JMX Server

**Port**: 1099 (configurable)

**MBeans Available**:
- `org.prf.monitoring:type=ScenarioRunner` - Scenario execution metrics
- `org.prf.monitoring:type=ThreadPoolMonitor` - Thread pool monitoring
- `org.prf.monitoring:type=Metrics` - Custom metrics

**Connect with**:
- `jconsole` (included with JDK)
- `jvisualvm` (included with JDK)
- `jmc` (Java Mission Control)

### 2. Metrics System

**Metric Types**:
- **Counters**: Incremental values (e.g., scenarios completed)
- **Gauges**: Point-in-time values (e.g., active connections)
- **Histograms**: Statistical distributions (e.g., processing times)
- **Meters**: Rates (e.g., operations per second)

**Usage**:
```clojure
;; Counter
(monitoring/increment-metric [:module :counter])
(monitoring/increment-metric [:module :counter] 5) ; +5

;; Gauge
(monitoring/gauge-metric 42 [:module :gauge])

;; Timing (milliseconds)
(monitoring/timing-metric 150 [:module :operation-time])

;; Rate
(monitoring/rate-metric 10.5 [:module :throughput])
```

### 3. Scenario Runner Monitoring

**Tracked Metrics**:
- Active scenarios count
- Scenarios completed/failed
- Processing rate (scenarios/sec)
- Error rate
- Average processing time

**Automatic Tracking**:
```clojure
;; Automatically tracked when using scenario runner
(monitoring/scenario-started "scenario-id" params)
(monitoring/scenario-completed "scenario-id" result)
(monitoring/scenario-failed "scenario-id" error)
```

### 4. Thread Pool Monitoring

**Tracked Metrics**:
- Pool utilization (%)
- Queue utilization (%)
- Active thread count
- Queue size
- Completed task count

**Bottleneck Detection**:
- **Warning**: Utilization > 90% OR queue utilization > 80%
- **Critical**: Utilization > 95% AND queue utilization > 90%

**Usage**:
```clojure
(let [executor (java.util.concurrent.Executors/newFixedThreadPool 4)]
  (monitoring/monitor-thread-pool "my-pool" executor)
  ;; Use executor...
  (monitoring/unmonitor-thread-pool "my-pool"))
```

### 5. Web Dashboard

**URLs**:
- Dashboard: `http://localhost:8090/monitoring`
- JSON API: `http://localhost:8090/api/monitoring`

**Features**:
- Real-time metrics visualization
- Thread pool utilization charts
- Bottleneck detection
- Scenario execution statistics
- Responsive design

## Configuration

Edit `config/monitoring.edn`:

```clojure
{:enabled true
 :jmx-port 1099
 :dashboard-port 8090
 :metrics-enabled true
 :thread-pool-monitoring true
 :sampling-interval-ms 1000
 :alert-thresholds {
   :high-utilization 0.9
   :high-queue-utilization 0.8
   :high-error-rate 0.05
   :low-processing-rate 0.1
 }
 :security {
   :jmx-authentication false
   :dashboard-authentication false
   :allowed-origins ["localhost" "127.0.0.1"]
 }}
```

## Integration Guide

### 1. Add to Your Main Application

```clojure
;; In your main namespace
(ns your.app.main
  (:require [resolver-sim.monitoring :as monitoring]))

(defn -main [& args]
  (monitoring/init-monitoring!)
  (try
    ;; Your application logic here
    (finally
      (monitoring/shutdown-monitoring!))))
```

### 2. Monitor Key Thread Pools

```clojure
;; In your executor creation code
(let [scenario-executor (java.util.concurrent.Executors/newFixedThreadPool 8)]
  (monitoring/monitor-thread-pool "scenario-executor" scenario-executor)
  
  ;; Use executor in your application
  )
```

### 3. Instrument Critical Code Paths

```clojure
;; Before critical operation
(monitoring/increment-metric [:operations :started])

;; After operation completes
(let [start-time (System/currentTimeMillis)
      result (perform-operation)
      duration (- (System/currentTimeMillis) start-time)]
  (monitoring/increment-metric [:operations :completed])
  (monitoring/timing-metric duration [:operation :duration])
  result)

;; On error
(catch Exception e
  (monitoring/increment-metric [:operations :failed])
  (throw e))
```

## Performance Impact

**Expected Overhead**: < 1% CPU, negligible memory

**Optimizations**:
- Atomic operations for thread safety
- Sampling-based monitoring (1s interval)
- Lazy computation of derived metrics
- Minimal locking

## Troubleshooting

### Monitoring Not Starting

**Check**:
```bash
# Verify configuration
cat config/monitoring.edn

# Check if monitoring is enabled
clojure -M -e "(require '[resolver-sim.config :as config]) (println (config/monitoring-enabled?))"
```

### Dashboard Not Accessible

**Check**:
```bash
# Verify port is listening
lsof -i :8090

# Check for errors
tail -f logs/monitoring.log

# Test with curl
curl http://localhost:8090/api/monitoring
```

### JMX Connection Issues

**Check**:
```bash
# Verify JMX port
lsof -i :1099

# Test JMX connection
jconsole

# Check firewall
sudo ufw status | grep 1099
```

## Advanced Usage

### Custom MBeans

Create your own JMX MBeans:

```clojure
(require '[resolver-sim.monitoring.jmx :as jmx])

(jmx/defmbean MyComponentMXBean
  [(getStatus ["String"])
   (getMetrics ["[Ljava.lang.String;"])
   (reset ["void"])])

(defn create-my-mbean []
  (reify MyComponentMXBean
    (getStatus [this] "Running")
    (getMetrics [this] (into-array String ["metric1=42" "metric2=100"]))
    (reset [this] (reset-component!))))

(jmx/register-mbean (create-my-mbean) "org.prf.monitoring:type=MyComponent")
```

### Remote JMX Access

For remote monitoring (use with caution):

```bash
# Add to JVM startup arguments
java -Dcom.sun.management.jmxremote \
     -Dcom.sun.management.jmxremote.port=1099 \
     -Dcom.sun.management.jmxremote.authenticate=false \
     -Dcom.sun.management.jmxremote.ssl=false \
     -Djava.rmi.server.hostname=your-server-ip \
     -jar your-app.jar
```

**Warning**: Remote JMX without authentication is insecure. Use only in trusted networks.

## Metrics Reference

### Standard Metrics

| Metric Key | Type | Description |
|------------|------|-------------|
| `[:scenario :active-count]` | Gauge | Currently active scenarios |
| `[:scenario :completed-count]` | Counter | Scenarios completed successfully |
| `[:scenario :failed-count]` | Counter | Scenarios that failed |
| `[:scenario :processing-time]` | Histogram | Scenario processing time (ms) |
| `[:thread-pool <name> :active-count]` | Gauge | Active threads in pool |
| `[:thread-pool <name> :queue-size]` | Gauge | Tasks waiting in queue |
| `[:thread-pool <name> :utilization]` | Gauge | Pool utilization (%) |
| `[:thread-pool <name> :queue-utilization]` | Gauge | Queue utilization (%) |

### Custom Metrics

Add your own metrics following the pattern:
```clojure
;; Module-specific metrics
(monitoring/increment-metric [:my-module :operations])
(monitoring/timing-metric duration [:my-module :operation-time])

;; Feature-specific metrics
(monitoring/gauge-metric value [:feature :active-users])
```

## Alerting (Future Enhancement)

Planned alerting system:

```clojure
;; Future API - not yet implemented
(monitoring/add-alert-rule!
  {:metric [:thread-pool :utilization]
   :threshold 0.9
   :comparison :>
   :severity :warning
   :notification :email
   :message "High thread pool utilization"})
```

## Best Practices

### 1. Monitor All Critical Thread Pools

```clojure
;; Monitor all executors
(monitoring/monitor-thread-pool "scenario-executor" scenario-executor)
(monitoring/monitor-thread-pool "invariant-checker" invariant-executor)
(monitoring/monitor-thread-pool "evidence-processor" evidence-executor)
```

### 2. Use Meaningful Metric Names

```clojure
;; Good
(monitoring/increment-metric [:scenario :execution :completed])

;; Avoid
(monitoring/increment-metric [:temp :counter1])
```

### 3. Monitor Error Rates

```clojure
;; Track different error types
(monitoring/increment-metric [:errors :validation])
(monitoring/increment-metric [:errors :timeout])
(monitoring/increment-metric [:errors :invariant-violation])
```

### 4. Use Histograms for Performance Metrics

```clojure
;; Track processing times
(monitoring/timing-metric duration [:scenario :processing-time])

;; Track queue wait times
(monitoring/timing-metric wait-time [:queue :wait-time])
```

### 5. Reset Metrics Appropriately

```clojure
;; Reset for specific test runs
(monitoring/reset-metrics!)

;; Or reset specific components
(monitoring/reset-scenario-statistics!)
```

## Roadmap

### ✅ Implemented
- Core monitoring infrastructure
- JMX server and MBeans
- Metrics collection system
- Thread pool monitoring
- Scenario runner monitoring
- Web dashboard

### 🚧 In Progress
- Advanced bottleneck detection
- Historical data collection
- Alerting system

### 📋 Planned
- Machine learning for anomaly detection
- Predictive scaling recommendations
- Distributed monitoring support
- Enhanced visualization
- Alert notification integrations

## Support

**Issues**: Report monitoring issues in the main PRF issue tracker

**Questions**: Check the monitoring FAQ in `docs/MONITORING_FAQ.md`

**Contributing**: See `CONTRIBUTING.md` for monitoring contribution guidelines

## License

The monitoring system is part of PRF and inherits its license terms.
