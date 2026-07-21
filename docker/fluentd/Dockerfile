# ---------------------------------------------------------------------------
# Fluentd with the OpenSearch output plugin.
#
# The official image ships a small core and expects outputs to be added, so
# there is no published tag that can write to OpenSearch. Building this here
# rather than using some third-party "fluentd-with-everything" image keeps the
# plugin set explicit and reviewable - a log shipper reads every log line the
# platform produces, which makes its supply chain worth being deliberate about.
# ---------------------------------------------------------------------------
ARG FLUENTD_VERSION=v1.19-debian-1
FROM fluent/fluentd:${FLUENTD_VERSION}

# Plugin installation needs root; the process drops back to the unprivileged
# fluent user for the actual run.
USER root

# Pinned. An unpinned gem install makes the image's behaviour depend on the day
# it was built, which is exactly the class of surprise a lab should not teach.
ARG OPENSEARCH_PLUGIN_VERSION=1.1.5
RUN gem install fluent-plugin-opensearch --version "${OPENSEARCH_PLUGIN_VERSION}" --no-document \
 && gem sources --clear-all \
 && rm -rf /home/fluent/.gem/ruby/*/cache/*.gem

# Buffers and position files live here, on a named volume: a restart must
# resume where it stopped rather than re-ship everything.
RUN mkdir -p /var/lib/fluentd/buffer && chown -R fluent:fluent /var/lib/fluentd

USER fluent
