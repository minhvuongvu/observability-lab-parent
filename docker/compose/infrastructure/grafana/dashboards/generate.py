#!/usr/bin/env python3
"""Generates the Grafana dashboards.

    python3 infrastructure/grafana/dashboards/generate.py   # from the repo root

THIS FILE IS THE SOURCE OF TRUTH. The .json files beside it are generated and
will be overwritten - edit here, not there. Grafana itself also serves them
read-only (`allowUiUpdates: false`), so the file on disk cannot be silently
replaced by someone saving in the browser either.

Written as a generator rather than by hand so that every dashboard shares the
same variables, units, colour rules and panel geometry. A set of dashboards that
disagree about what "service" means, or that colour errors differently on each
page, is the thing that makes people stop trusting them.

Conventions applied throughout:
  - One unit per panel. Never two y-axes: requests-per-second and seconds on one
    axis makes both unreadable, and it is the most common way a dashboard
    becomes decorative.
  - Status colours are reserved. Red means error, orange means warning, green
    means healthy - everywhere, including log levels and settlement outcomes.
    They are never reused as "series 4".
  - Percentiles come from histogram buckets via histogram_quantile, never from
    pre-computed percentiles, which cannot be aggregated across instances.
  - A query that legitimately matches nothing gets `or vector(0)`, so a healthy
    system renders zero rather than "No data" - which reads as a broken panel.
"""
import json
import pathlib

ROOT = pathlib.Path("infrastructure/grafana/dashboards")

# Reserved status colours, used consistently everywhere.
GREEN, ORANGE, RED, BLUE, TEXT = "green", "orange", "red", "blue", "text"


def ds_var(default="prometheus", label="Store"):
    return {
        "name": "datasource",
        "label": label,
        "description": "Prometheus holds 24h and evaluates the alerts; VictoriaMetrics holds "
                       "the same samples for 30d. Both answer PromQL.",
        "type": "datasource",
        "query": default,
        "current": {"text": "Prometheus", "value": "prometheus"},
    }


def service_var(metric="up"):
    return {
        "name": "service",
        "label": "Service",
        "type": "query",
        "datasource": {"type": "prometheus", "uid": "${datasource}"},
        "query": {"query": f"label_values({metric}, service)", "refId": "service"},
        "refresh": 2,
        "includeAll": True,
        "allValue": ".*",
        "multi": True,
        "current": {"text": "All", "value": "$__all"},
    }


def target(expr, legend=None, ref="A", instant=False):
    t = {"refId": ref, "expr": expr}
    if legend:
        t["legendFormat"] = legend
    if instant:
        t["instant"] = True
    return t


def stat(id_, title, expr, x, y, w=6, h=4, unit="short", desc=None,
         steps=None, graph="none", decimals=0):
    p = {
        "type": "stat", "id": id_, "title": title,
        "datasource": {"type": "prometheus", "uid": "${datasource}"},
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
        "targets": [target(expr, instant=True)],
        "options": {
            "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
            "textMode": "value", "colorMode": "value" if steps else "none",
            "graphMode": graph, "justifyMode": "center",
        },
        "fieldConfig": {"defaults": {"unit": unit, "decimals": decimals}, "overrides": []},
    }
    if desc:
        p["description"] = desc
    if steps:
        p["fieldConfig"]["defaults"]["thresholds"] = {
            "mode": "absolute",
            "steps": [{"color": c, "value": v} for c, v in steps],
        }
    return p


def timeseries(id_, title, targets, x, y, w=12, h=8, unit="short", desc=None,
               overrides=None, fill=8, stack=False, minimum=0, points="never"):
    custom = {
        "drawStyle": "line", "lineWidth": 2, "fillOpacity": fill,
        "showPoints": points, "pointSize": 8, "spanNulls": False,
    }
    if stack:
        custom["stacking"] = {"mode": "normal"}
    p = {
        "type": "timeseries", "id": id_, "title": title,
        "datasource": {"type": "prometheus", "uid": "${datasource}"},
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
        "targets": targets,
        "options": {
            "legend": {"displayMode": "list", "placement": "bottom", "showLegend": True, "calcs": []},
            "tooltip": {"mode": "multi", "sort": "desc"},
        },
        "fieldConfig": {
            "defaults": {"unit": unit, "custom": custom},
            "overrides": overrides or [],
        },
    }
    if minimum is not None:
        p["fieldConfig"]["defaults"]["min"] = minimum
    if desc:
        p["description"] = desc
    return p


def table(id_, title, targets, x, y, w=12, h=8, desc=None, unit="short", transformations=None):
    p = {
        "type": "table", "id": id_, "title": title,
        "datasource": {"type": "prometheus", "uid": "${datasource}"},
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
        "targets": [dict(t, instant=True, format="table") for t in targets],
        "options": {"showHeader": True, "sortBy": [{"displayName": "Value", "desc": True}]},
        "fieldConfig": {"defaults": {"unit": unit, "custom": {"align": "auto"}}, "overrides": []},
    }
    if transformations:
        p["transformations"] = transformations
    if desc:
        p["description"] = desc
    return p


def row(id_, title, y):
    return {"type": "row", "id": id_, "title": title,
            "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "collapsed": False}


def color_override(name, color):
    return {"matcher": {"id": "byName", "options": name},
            "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": color}}]}


def dashboard(uid, title, description, tags, panels, templating=None, refresh="30s",
              time_from="now-1h"):
    return {
        "uid": uid, "title": title, "description": description,
        "tags": ["observability-lab"] + tags,
        "timezone": "browser", "schemaVersion": 39, "version": 1,
        "editable": False, "refresh": refresh,
        "time": {"from": time_from, "to": "now"},
        "templating": {"list": templating if templating is not None
                       else [ds_var(), service_var()]},
        "panels": panels,
    }


def write(folder, name, doc):
    d = ROOT / folder
    d.mkdir(parents=True, exist_ok=True)
    (d / name).write_text(json.dumps(doc, indent=2) + "\n")
    print(f"  {folder}/{name}")


# ---------------------------------------------------------------------------
# 1. Platform Overview — the landing page.
# ---------------------------------------------------------------------------
overview = dashboard(
    "lab-platform-overview", "Platform — Overview",
    "The landing page. Golden signals for both services plus the health of every telemetry "
    "pipeline, with links out to the focused dashboards. Start here; go elsewhere once you know "
    "which thing is unhappy. See docs/Observability.md.",
    ["overview"],
    [
        row(100, "Are the services up and serving?", 0),
        stat(1, "Services up", 'sum(up{job="observability-lab-services"})', 0, 1,
             desc="A target that stops answering scrapes is itself the signal. A service that "
                  "has died reports nothing at all, which a health-based check cannot distinguish "
                  "from silence.",
             steps=[(RED, None), (GREEN, 2)]),
        stat(2, "Request rate", 'sum(rate(http_server_requests_seconds_count{service=~"$service"}[$__rate_interval]))',
             6, 1, unit="reqps", decimals=2, graph="area"),
        stat(3, "Error ratio",
             '(sum(rate(http_server_requests_seconds_count{service=~"$service",outcome=~"SERVER_ERROR"}[$__rate_interval])) or vector(0))'
             ' / clamp_min(sum(rate(http_server_requests_seconds_count{service=~"$service"}[$__rate_interval])), 0.001)',
             12, 1, unit="percentunit", decimals=2,
             desc="A ratio, not a count: ten errors per second is a catastrophe at low traffic and "
                  "noise at high traffic, and only the ratio means the same thing at both.",
             steps=[(GREEN, None), (ORANGE, 0.01), (RED, 0.05)]),
        stat(4, "p99 latency",
             'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{service=~"$service"}[$__rate_interval])))',
             18, 1, unit="s", decimals=3,
             desc="Computed server-side from bucket counts, which is the only form that aggregates "
                  "correctly across instances.",
             steps=[(GREEN, None), (ORANGE, 0.5), (RED, 1)]),

        row(101, "Golden signals", 5),
        timeseries(5, "Traffic and errors",
                   [target('sum by (outcome) (rate(http_server_requests_seconds_count{service=~"$service"}[$__rate_interval]))',
                           "{{outcome}}")],
                   0, 6, w=12, unit="reqps", stack=True,
                   desc="Stacked by status class. Same unit throughout, so stacking is honest here.",
                   overrides=[color_override("SUCCESS", GREEN), color_override("CLIENT_ERROR", ORANGE),
                              color_override("SERVER_ERROR", RED)]),
        timeseries(6, "Latency percentiles",
                   [target('histogram_quantile(0.50, sum by (le) (rate(http_server_requests_seconds_bucket{service=~"$service"}[$__rate_interval])))', "p50"),
                    target('histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{service=~"$service"}[$__rate_interval])))', "p95", "B"),
                    target('histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{service=~"$service"}[$__rate_interval])))', "p99", "C")],
                   12, 6, w=12, unit="s", fill=0,
                   desc="On its own panel rather than sharing an axis with the rate: "
                        "requests-per-second and seconds are different units, and a dual axis "
                        "makes both unreadable."),

        row(102, "Business", 14),
        timeseries(7, "Orders accepted vs settled",
                   [target('sum(rate(lab_orders_accepted_total[$__rate_interval]))', "accepted"),
                    target('sum(rate(lab_orders_settled_total[$__rate_interval]))', "settled", "B")],
                   0, 15, w=12, unit="ops",
                   desc="The signal with no technical equivalent: every process metric can be green "
                        "while orders are accepted and never answered. Divergence is the alarm."),
        stat(8, "Outbox backlog", 'sum(lab_outbox_pending_events)', 12, 15, w=6, graph="area",
             desc="Events written but not yet acknowledged by Kafka. Should sit at zero.",
             steps=[(GREEN, None), (ORANGE, 10), (RED, 50)]),
        stat(9, "Unsettled orders",
             'sum(increase(lab_orders_accepted_total[$__range])) - sum(increase(lab_orders_settled_total[$__range]))',
             18, 15, w=6, steps=[(TEXT, None), (ORANGE, 1), (RED, 10)]),

        row(103, "Is the telemetry itself healthy?", 19),
        stat(10, "Scrape targets up", 'sum(up)', 0, 20, w=4, unit="short",
             desc="Including the observability components. The pipeline that carries the signals "
                  "is itself something that can fail."),
        stat(11, "Log lines/s",
             'sum(rate(promtail_sent_entries_total[$__rate_interval])) or vector(0)',
             4, 20, w=5, unit="short", decimals=2, graph="area",
             desc="From Promtail. Zero while the services are idle is normal; zero while they are "
                  "serving traffic means the log pipeline has broken."),
        stat(12, "Spans/s accepted",
             'sum(rate(otelcol_receiver_accepted_spans_total[$__rate_interval])) or vector(0)',
             9, 20, w=5, unit="short", decimals=2, graph="area"),
        stat(13, "Spans/s refused",
             'sum(rate(otelcol_receiver_refused_spans_total[$__rate_interval])) or vector(0)',
             14, 20, w=5, unit="short", decimals=2,
             desc="Non-zero means the collector is shedding load, so traces are incomplete — and "
                  "an incomplete trace is worse than none, because it looks complete.",
             steps=[(GREEN, None), (RED, 0.001)]),
        stat(14, "Profile series",
             'count(count by (service_name) (pyroscope_ingester_head_series)) or vector(0)',
             19, 20, w=5, unit="short"),
    ])

# ---------------------------------------------------------------------------
# 2. HTTP
# ---------------------------------------------------------------------------
http = dashboard(
    "lab-http", "HTTP — Requests",
    "Request rate, latency and status by endpoint. The `uri` label is the *templated* path "
    "(/api/v1/orders/{orderNumber}), never the raw one — a metric per order number would be one "
    "time series per order. See docs/Metrics.md.",
    ["http"],
    [
        row(100, "Overall", 0),
        stat(1, "Requests/s", 'sum(rate(http_server_requests_seconds_count{service=~"$service"}[$__rate_interval]))',
             0, 1, unit="reqps", decimals=2, graph="area"),
        stat(2, "Errors/s",
             'sum(rate(http_server_requests_seconds_count{service=~"$service",outcome="SERVER_ERROR"}[$__rate_interval])) or vector(0)',
             6, 1, unit="reqps", decimals=3, steps=[(TEXT, None), (RED, 0.001)],
             desc="Zero renders as zero rather than as 'No data': an empty panel is "
                  "indistinguishable from a broken one at a glance."),
        stat(3, "p95", 'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{service=~"$service"}[$__rate_interval])))',
             12, 1, unit="s", decimals=3),
        stat(4, "Under 500ms",
             'sum(rate(http_server_requests_seconds_bucket{service=~"$service",le="0.5"}[$__rate_interval]))'
             ' / clamp_min(sum(rate(http_server_requests_seconds_count{service=~"$service"}[$__rate_interval])), 0.001)',
             18, 1, unit="percentunit", decimals=3,
             desc="An exact bucket ratio, not an interpolation — 500ms is one of the SLO boundaries "
                  "given its own bucket in application.yml.",
             steps=[(RED, None), (ORANGE, 0.95), (GREEN, 0.99)]),

        row(101, "By service and endpoint", 5),
        timeseries(5, "Rate by service",
                   [target('sum by (service) (rate(http_server_requests_seconds_count{service=~"$service"}[$__rate_interval]))', "{{service}}")],
                   0, 6, w=8, unit="reqps"),
        timeseries(6, "Rate by status class",
                   [target('sum by (outcome) (rate(http_server_requests_seconds_count{service=~"$service"}[$__rate_interval]))', "{{outcome}}")],
                   8, 6, w=8, unit="reqps", stack=True,
                   overrides=[color_override("SUCCESS", GREEN), color_override("CLIENT_ERROR", ORANGE),
                              color_override("SERVER_ERROR", RED)]),
        timeseries(7, "Latency percentiles",
                   [target('histogram_quantile(0.50, sum by (le) (rate(http_server_requests_seconds_bucket{service=~"$service"}[$__rate_interval])))', "p50"),
                    target('histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{service=~"$service"}[$__rate_interval])))', "p95", "B"),
                    target('histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{service=~"$service"}[$__rate_interval])))', "p99", "C")],
                   16, 6, w=8, unit="s", fill=0),

        row(102, "Endpoints", 14),
        table(8, "Busiest endpoints",
              [target('topk(15, sum by (service, method, uri) (rate(http_server_requests_seconds_count{service=~"$service"}[$__range])))')],
              0, 15, w=12, unit="reqps",
              desc="Templated URIs. Ordered by rate, so the first row is where load actually is."),
        table(9, "Slowest endpoints",
              [target('topk(15, sum by (service, method, uri) (rate(http_server_requests_seconds_sum{service=~"$service"}[$__range]))'
                      ' / clamp_min(sum by (service, method, uri) (rate(http_server_requests_seconds_count{service=~"$service"}[$__range])), 0.001))')],
              12, 15, w=12, unit="s",
              desc="Mean latency per endpoint. A mean is acceptable here because the panel is a "
                   "shortlist to investigate, not the number anything is alerted on."),
    ])

# ---------------------------------------------------------------------------
# 3. JVM
# ---------------------------------------------------------------------------
jvm = dashboard(
    "lab-jvm", "JVM — Memory, GC, Threads",
    "Runtime health of both service JVMs. Memory is split by pool rather than shown as one "
    "number: 'heap is full' and 'metaspace is full' have completely different causes and fixes.",
    ["jvm"],
    [
        row(100, "Headline", 0),
        stat(1, "Heap used", 'sum(jvm_memory_used_bytes{service=~"$service",area="heap"})', 0, 1, unit="bytes", graph="area"),
        stat(2, "Heap utilisation",
             'sum(jvm_memory_used_bytes{service=~"$service",area="heap"}) / clamp_min(sum(jvm_memory_max_bytes{service=~"$service",area="heap"}), 1)',
             6, 1, unit="percentunit", decimals=2,
             steps=[(GREEN, None), (ORANGE, 0.75), (RED, 0.9)]),
        stat(3, "GC time/s", 'sum(rate(jvm_gc_pause_seconds_sum{service=~"$service"}[$__rate_interval]))',
             12, 1, unit="percentunit", decimals=4,
             desc="Seconds paused per second. More useful than a pause count: ten 1ms pauses and "
                  "one 500ms pause are very different experiences for a caller.",
             steps=[(GREEN, None), (ORANGE, 0.02), (RED, 0.05)]),
        stat(4, "Live threads", 'sum(jvm_threads_live_threads{service=~"$service"})', 18, 1, graph="area"),

        row(101, "Memory", 5),
        timeseries(5, "Heap by pool",
                   [target('sum by (service, id) (jvm_memory_used_bytes{service=~"$service",area="heap"})', "{{service}} {{id}}")],
                   0, 6, w=12, unit="bytes",
                   desc="Eden filling and emptying is the sawtooth of normal operation. Old Gen "
                        "climbing and never falling after a GC is the shape of a leak."),
        timeseries(6, "Non-heap by pool",
                   [target('sum by (service, id) (jvm_memory_used_bytes{service=~"$service",area="nonheap"})', "{{service}} {{id}}")],
                   12, 6, w=12, unit="bytes",
                   desc="Metaspace and code cache. A metaspace leak looks nothing like a heap leak "
                        "and is invisible on the panel beside this one."),

        row(102, "Garbage collection and threads", 14),
        timeseries(7, "GC pause rate by cause",
                   [target('sum by (service, cause) (rate(jvm_gc_pause_seconds_sum{service=~"$service"}[$__rate_interval]))', "{{service}} {{cause}}")],
                   0, 15, w=8, unit="percentunit"),
        timeseries(8, "GC pause p99",
                   [target('histogram_quantile(0.99, sum by (service, le) (rate(jvm_gc_pause_seconds_bucket{service=~"$service"}[$__rate_interval])))', "{{service}}")],
                   8, 15, w=8, unit="s", fill=0),
        timeseries(9, "Threads",
                   [target('sum by (service) (jvm_threads_live_threads{service=~"$service"})', "{{service}} live"),
                    target('sum by (service) (jvm_threads_daemon_threads{service=~"$service"})', "{{service}} daemon", "B")],
                   16, 15, w=8, fill=0),

        row(103, "Process", 23),
        timeseries(10, "CPU usage",
                   [target('process_cpu_usage{service=~"$service"}', "{{service}} process"),
                    target('system_cpu_usage{service=~"$service"}', "{{service}} system", "B")],
                   0, 24, w=12, unit="percentunit"),
        timeseries(11, "Classes loaded",
                   [target('sum by (service) (jvm_classes_loaded_classes{service=~"$service"})', "{{service}}")],
                   12, 24, w=12, fill=0,
                   desc="Climbing without limit is the signature of a class-loader leak, which "
                        "exhausts metaspace rather than heap."),
    ])

# ---------------------------------------------------------------------------
# 4. Databases
# ---------------------------------------------------------------------------
databases = dashboard(
    "lab-databases", "Databases — Connection Pools",
    "PostgreSQL (Order) and Oracle (Inventory) as the services see them. Pool saturation is the "
    "shape almost every 'the site is slow' incident actually has: threads waiting for a connection "
    "that never frees. Query-level latency lives on the Traces dashboard, where each statement is "
    "a span.",
    ["database"],
    [
        row(100, "Saturation", 0),
        stat(1, "Active connections", 'sum(hikaricp_connections_active{service=~"$service"})', 0, 1),
        stat(2, "Threads waiting", 'sum(hikaricp_connections_pending{service=~"$service"})', 6, 1,
             desc="Any sustained non-zero value here means requests are queueing on the pool. This "
                  "is the number to alert on, not connection count.",
             steps=[(GREEN, None), (ORANGE, 1), (RED, 5)]),
        stat(3, "Acquire p99",
             'histogram_quantile(0.99, sum by (le) (rate(hikaricp_connections_acquire_seconds_bucket{service=~"$service"}[$__rate_interval])))',
             12, 1, unit="s", decimals=4),
        stat(4, "Timeouts", 'sum(increase(hikaricp_connections_timeout_total{service=~"$service"}[$__range])) or vector(0)',
             18, 1, steps=[(GREEN, None), (RED, 1)],
             desc="A connection request that gave up. Every one of these is a failed user request."),

        row(101, "Per pool", 5),
        timeseries(5, "Connections by pool",
                   [target('sum by (pool) (hikaricp_connections_active{service=~"$service"})', "{{pool}} active"),
                    target('sum by (pool) (hikaricp_connections_idle{service=~"$service"})', "{{pool}} idle", "B"),
                    target('sum by (pool) (hikaricp_connections_pending{service=~"$service"})', "{{pool}} pending", "C")],
                   0, 6, w=12,
                   desc="Both pools on one panel because they share a unit and the comparison is "
                        "the point: Oracle is measurably slower to hand out a connection, which is "
                        "why its timeout is configured higher.",
                   overrides=[{"matcher": {"id": "byRegexp", "options": ".*pending"},
                               "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": RED}}]}]),
        timeseries(6, "Connection acquire time",
                   [target('histogram_quantile(0.95, sum by (pool, le) (rate(hikaricp_connections_acquire_seconds_bucket{service=~"$service"}[$__rate_interval])))', "{{pool}} p95")],
                   12, 6, w=12, unit="s", fill=0),

        row(102, "Usage", 14),
        timeseries(7, "Connection usage time",
                   [target('histogram_quantile(0.95, sum by (pool, le) (rate(hikaricp_connections_usage_seconds_bucket{service=~"$service"}[$__rate_interval])))', "{{pool}} p95")],
                   0, 15, w=12, unit="s", fill=0,
                   desc="How long a connection is held once acquired. Rising usage time with flat "
                        "traffic means queries are getting slower, not that there are more of them."),
        timeseries(8, "Pool size",
                   [target('sum by (pool) (hikaricp_connections{service=~"$service"})', "{{pool}} total"),
                    target('sum by (pool) (hikaricp_connections_max{service=~"$service"})', "{{pool}} max", "B")],
                   12, 15, w=12, fill=0),
    ])

# ---------------------------------------------------------------------------
# 5. Redis
# ---------------------------------------------------------------------------
redis = dashboard(
    "lab-redis", "Redis — Cache and Commands",
    "The cache as the services see it, through the Lettuce client and Spring's cache abstraction. "
    "Hit ratio is the number that decides whether the cache is earning its keep; command latency "
    "is the number that decides whether it has become slower than the database it fronts. "
    "See docs/Redis.md.",
    ["redis", "cache"],
    [
        row(100, "Effectiveness", 0),
        stat(1, "Cache hit ratio",
             'sum(rate(cache_gets_total{service=~"$service",result="hit"}[$__rate_interval]))'
             ' / clamp_min(sum(rate(cache_gets_total{service=~"$service",result=~"hit|miss"}[$__rate_interval])), 0.001)',
             0, 1, w=8, unit="percentunit", decimals=3,
             desc="A cache with a low hit ratio is pure overhead: every lookup costs a network "
                  "round trip and still ends at the database.",
             steps=[(RED, None), (ORANGE, 0.5), (GREEN, 0.8)]),
        stat(2, "Command latency (mean)",
             'sum(rate(lettuce_command_completion_seconds_sum{service=~"$service"}[$__rate_interval]))'
             ' / clamp_min(sum(rate(lettuce_command_completion_seconds_count{service=~"$service"}[$__rate_interval])), 0.001)',
             8, 1, w=8, unit="s", decimals=5,
             steps=[(GREEN, None), (ORANGE, 0.005), (RED, 0.02)]),
        stat(3, "Commands/s", 'sum(rate(lettuce_command_completion_seconds_count{service=~"$service"}[$__rate_interval]))',
             16, 1, w=8, unit="ops", decimals=2, graph="area"),

        row(101, "Hit and miss", 5),
        timeseries(4, "Cache gets by result",
                   [target('sum by (cache, result) (rate(cache_gets_total{service=~"$service"}[$__rate_interval]))', "{{cache}} {{result}}")],
                   0, 6, w=12, unit="ops",
                   desc="Split per cache. The orders cache and the stock cache have deliberately "
                        "different TTLs, so they should not be read as one number.",
                   overrides=[{"matcher": {"id": "byRegexp", "options": ".*hit"},
                               "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": GREEN}}]},
                              {"matcher": {"id": "byRegexp", "options": ".*miss"},
                               "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": ORANGE}}]}]),
        timeseries(5, "Hit ratio over time",
                   [target('sum by (cache) (rate(cache_gets_total{service=~"$service",result="hit"}[$__rate_interval]))'
                           ' / clamp_min(sum by (cache) (rate(cache_gets_total{service=~"$service",result=~"hit|miss"}[$__rate_interval])), 0.001)',
                           "{{cache}}")],
                   12, 6, w=12, unit="percentunit", fill=0,
                   desc="A ratio that falls off a cliff usually means an eviction storm or a "
                        "deployment that changed the key shape."),

        row(102, "Client", 14),
        timeseries(6, "Command latency percentiles",
                   [target('histogram_quantile(0.50, sum by (le) (rate(lettuce_command_completion_seconds_bucket{service=~"$service"}[$__rate_interval])))', "p50"),
                    target('histogram_quantile(0.99, sum by (le) (rate(lettuce_command_completion_seconds_bucket{service=~"$service"}[$__rate_interval])))', "p99", "B")],
                   0, 15, w=12, unit="s", fill=0),
        timeseries(7, "Commands by type",
                   [target('topk(10, sum by (command) (rate(lettuce_command_completion_seconds_count{service=~"$service"}[$__rate_interval])))', "{{command}}")],
                   12, 15, w=12, unit="ops"),
    ])

# ---------------------------------------------------------------------------
# 6. Kafka
# ---------------------------------------------------------------------------
kafka = dashboard(
    "lab-kafka", "Kafka — Producers, Consumers, Lag",
    "The event backbone as the clients see it. Consumer lag is the single most important number: "
    "it is the only one that says whether the asynchronous half of the order flow is keeping up. "
    "See docs/Kafka.md.",
    ["kafka", "messaging"],
    [
        row(100, "Is it keeping up?", 0),
        stat(1, "Max consumer lag", 'max(kafka_consumer_fetch_manager_records_lag_max{service=~"$service"})',
             0, 1, w=8, graph="area",
             desc="Records behind the head of the partition. Sustained growth means consumption is "
                  "slower than production, and every order in that backlog is one whose stock is "
                  "not yet reserved.",
             steps=[(GREEN, None), (ORANGE, 100), (RED, 1000)]),
        stat(2, "Produced/s", 'sum(rate(kafka_producer_record_send_total{service=~"$service"}[$__rate_interval]))',
             8, 1, w=8, unit="ops", decimals=2, graph="area"),
        stat(3, "Consumed/s", 'sum(rate(kafka_consumer_fetch_manager_records_consumed_total{service=~"$service"}[$__rate_interval]))',
             16, 1, w=8, unit="ops", decimals=2, graph="area"),

        row(101, "Throughput and lag", 5),
        timeseries(4, "Lag by topic and partition",
                   [target('sum by (topic, partition) (kafka_consumer_fetch_manager_records_lag{service=~"$service"})', "{{topic}}[{{partition}}]")],
                   0, 6, w=12,
                   desc="Per partition, not summed. Lag concentrated on one partition means an "
                        "uneven key distribution or one stuck consumer — a sum hides both."),
        timeseries(5, "Produce vs consume",
                   [target('sum by (service) (rate(kafka_producer_record_send_total{service=~"$service"}[$__rate_interval]))', "{{service}} produced"),
                    target('sum by (service) (rate(kafka_consumer_fetch_manager_records_consumed_total{service=~"$service"}[$__rate_interval]))', "{{service}} consumed", "B")],
                   12, 6, w=12, unit="ops", fill=0),

        row(102, "Client health", 14),
        timeseries(6, "Producer request latency",
                   [target('sum by (service) (kafka_producer_request_latency_avg{service=~"$service"})', "{{service}}")],
                   0, 15, w=8, unit="ms", fill=0),
        timeseries(7, "Rebalances",
                   [target('sum by (service) (rate(kafka_consumer_coordinator_rebalance_total{service=~"$service"}[$__rate_interval]))', "{{service}}")],
                   8, 15, w=8, unit="ops",
                   desc="A rebalance stops consumption on every partition in the group while it "
                        "happens. Frequent rebalances usually mean max.poll.interval.ms is too "
                        "short for how long a record takes to handle."),
        timeseries(8, "Producer errors",
                   [target('sum by (service) (rate(kafka_producer_record_error_total{service=~"$service"}[$__rate_interval])) or vector(0)', "{{service}}")],
                   16, 15, w=8, unit="ops",
                   overrides=[{"matcher": {"id": "byRegexp", "options": ".*"},
                               "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": RED}}]}]),

        row(103, "Outbox and dead letters", 23),
        timeseries(9, "Outbox backlog",
                   [target('sum by (service) (lab_outbox_pending_events)', "{{service}}")],
                   0, 24, w=12,
                   desc="Events owed to Kafka. The outbox makes delivery retryable; a backlog that "
                        "does not drain means the relay cannot reach the broker at all."),
        timeseries(10, "Dead-lettered messages",
                   [target('sum by (service, reason) (rate(lab_messages_dead_lettered_total[$__rate_interval])) or vector(0)', "{{service}} {{reason}}")],
                   12, 24, w=12, unit="ops",
                   desc="Any dead letter is worth a look: by definition it exhausted its retries, "
                        "so nothing will pick it up automatically.",
                   overrides=[{"matcher": {"id": "byRegexp", "options": ".*"},
                               "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": RED}}]}]),
    ])

# ---------------------------------------------------------------------------
# 7. Traces
# ---------------------------------------------------------------------------
traces = dashboard(
    "lab-traces", "Traces — Span Metrics and Service Graph",
    "RED metrics and a service dependency graph, both derived by Tempo from the spans themselves — "
    "neither service was instrumented to produce them. Use the Explore link at the bottom to open "
    "an actual waterfall. See docs/Tracing.md.",
    ["traces"],
    [
        row(100, "Derived from spans", 0),
        stat(1, "Span rate", 'sum(rate(traces_spanmetrics_calls_total[$__rate_interval]))', 0, 1, w=6,
             unit="ops", decimals=2, graph="area"),
        stat(2, "Spans with error status",
             'sum(rate(traces_spanmetrics_calls_total{status_code="STATUS_CODE_ERROR"}[$__rate_interval])) or vector(0)',
             6, 1, w=6, unit="ops", decimals=3, steps=[(GREEN, None), (RED, 0.001)],
             desc="Only genuine faults set an error status. A rejected order is the system working "
                  "and deliberately does not appear here."),
        stat(3, "Service graph edges", 'count(count by (client, server) (traces_service_graph_request_total))',
             12, 1, w=6,
             desc="Distinct caller→callee pairs Tempo has observed. A new edge appearing is a new "
                  "dependency someone introduced."),
        stat(4, "Collector spans refused",
             'sum(increase(otelcol_receiver_refused_spans_total[$__range])) or vector(0)',
             18, 1, w=6, steps=[(GREEN, None), (RED, 1)]),

        row(101, "Latency by span", 5),
        timeseries(5, "Slowest span names (p95)",
                   [target('topk(10, histogram_quantile(0.95, sum by (span_name, le) (rate(traces_spanmetrics_latency_bucket[$__rate_interval]))))', "{{span_name}}")],
                   0, 6, w=12, unit="s", fill=0,
                   desc="Every JDBC statement, Kafka send and Redis command is a span, so this "
                        "reaches inside the request in a way HTTP metrics cannot."),
        timeseries(6, "Span rate by kind",
                   [target('sum by (span_kind) (rate(traces_spanmetrics_calls_total[$__rate_interval]))', "{{span_kind}}")],
                   12, 6, w=12, unit="ops", stack=True),

        row(102, "Service dependencies", 14),
        table(7, "Service graph",
              [target('sum by (client, server) (rate(traces_service_graph_request_total[$__range]))')],
              0, 15, w=12, unit="ops",
              desc="Caller and callee, observed rather than declared. Includes the asynchronous "
                   "edges: order-service → inventory-service is the Kafka round trip."),
        timeseries(8, "Edge latency (p95)",
                   [target('histogram_quantile(0.95, sum by (client, server, le) (rate(traces_service_graph_request_server_seconds_bucket[$__rate_interval])))',
                           "{{client}} → {{server}}")],
                   12, 15, w=12, unit="s", fill=0),

        row(103, "Top spans", 23),
        table(9, "Busiest spans",
              [target('topk(20, sum by (service, span_name, span_kind) (rate(traces_spanmetrics_calls_total[$__range])))')],
              0, 24, w=24, unit="ops"),
    ])

# ---------------------------------------------------------------------------
# 8. Profiles
# ---------------------------------------------------------------------------
profiles = {
    "uid": "lab-profiles",
    "title": "Profiles — CPU, Allocation, Locks",
    "description": "Continuous profiles from Pyroscope. Flame graphs are interactive and belong in "
                   "Explore rather than on a fixed panel, so this dashboard sets up the four views "
                   "and links to them. The most useful path is Explore → Tempo → a slow span → "
                   "Profiles tab, which shows the CPU that span actually burned. "
                   "See docs/Profiling.md.",
    "tags": ["observability-lab", "profiles"],
    "timezone": "browser", "schemaVersion": 39, "version": 1,
    "editable": False, "refresh": "1m",
    "time": {"from": "now-1h", "to": "now"},
    "templating": {"list": [{
        "name": "service",
        "label": "Service",
        "type": "custom",
        "query": "order-service,inventory-service",
        "current": {"text": "order-service", "value": "order-service"},
        "options": [
            {"text": "order-service", "value": "order-service", "selected": True},
            {"text": "inventory-service", "value": "inventory-service", "selected": False},
        ],
        "multi": False, "includeAll": False,
    }]},
    "panels": [
        {"type": "row", "id": 100, "title": "CPU", "gridPos": {"h": 1, "w": 24, "x": 0, "y": 0}},
        {
            "type": "flamegraph", "id": 1, "title": "CPU (itimer)",
            "description": "Where wall-clock time went. itimer rather than cpu, so time spent "
                           "blocked on a database is attributed rather than invisible.",
            "datasource": {"type": "grafana-pyroscope-datasource", "uid": "pyroscope"},
            "gridPos": {"h": 12, "w": 24, "x": 0, "y": 1},
            "targets": [{
                "refId": "A", "queryType": "profile",
                "profileTypeId": "process_cpu:cpu:nanoseconds:cpu:nanoseconds",
                "labelSelector": '{service_name="$service"}',
                "groupBy": [],
            }],
        },
        {"type": "row", "id": 101, "title": "Memory", "gridPos": {"h": 1, "w": 24, "x": 0, "y": 13}},
        {
            "type": "flamegraph", "id": 2, "title": "Allocation",
            "description": "What allocated the most. Most of this is short-lived garbage the "
                           "collector reclaims for free — compare with the live panel beside it.",
            "datasource": {"type": "grafana-pyroscope-datasource", "uid": "pyroscope"},
            "gridPos": {"h": 12, "w": 12, "x": 0, "y": 14},
            "targets": [{
                "refId": "A", "queryType": "profile",
                "profileTypeId": "memory:alloc_in_new_tlab_bytes:bytes:space:bytes",
                "labelSelector": '{service_name="$service"}',
                "groupBy": [],
            }],
        },
        {
            "type": "flamegraph", "id": 3, "title": "Live heap",
            "description": "What is still retained, from PYROSCOPE_ALLOC_LIVE. This is the one "
                           "that finds a leak: allocation volume alone says nothing about what is "
                           "being held, because most allocation is short-lived garbage the collector "
                           "reclaims for free.",
            "datasource": {"type": "grafana-pyroscope-datasource", "uid": "pyroscope"},
            "gridPos": {"h": 12, "w": 12, "x": 12, "y": 14},
            "targets": [{
                "refId": "A", "queryType": "profile",
                "profileTypeId": "memory:live:count:objects:count",
                "labelSelector": '{service_name="$service"}',
                "groupBy": [],
            }],
        },
        {"type": "row", "id": 102, "title": "Lock contention", "gridPos": {"h": 1, "w": 24, "x": 0, "y": 26}},
        {
            "type": "flamegraph", "id": 4, "title": "Lock contention",
            "description": "Which locks threads waited on, sampled for any lock held longer than "
                           "10ms. Contention is invisible in CPU profiles because a blocked thread "
                           "burns no CPU.",
            "datasource": {"type": "grafana-pyroscope-datasource", "uid": "pyroscope"},
            "gridPos": {"h": 12, "w": 24, "x": 0, "y": 27},
            "targets": [{
                "refId": "A", "queryType": "profile",
                "profileTypeId": "mutex:contentions:count:contentions:count",
                "labelSelector": '{service_name="$service"}',
                "groupBy": [],
            }],
        },
    ],
}

if __name__ == "__main__":
    print("Writing dashboards:")
    write("overview", "platform-overview.json", overview)
    write("http", "http.json", http)
    write("jvm", "jvm.json", jvm)
    write("data", "databases.json", databases)
    write("data", "redis.json", redis)
    write("messaging", "kafka.json", kafka)
    write("traces", "traces.json", traces)
    write("profiles", "profiles.json", profiles)
