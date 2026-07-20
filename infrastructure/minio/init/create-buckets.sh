#!/bin/sh
# ---------------------------------------------------------------------------
# Creates the invoice bucket and a least-privilege account for the services.
#
# The root credentials exist to administer MinIO, not to be embedded in an
# application. This provisions a separate user whose policy grants access to
# the invoice bucket and nothing else, which is what the Order Service will
# authenticate as.
#
# Idempotent: safe to re-run against an existing MinIO.
# ---------------------------------------------------------------------------
set -eu

ALIAS=lab
POLICY_NAME=invoice-readwrite
POLICY_FILE=/tmp/${POLICY_NAME}.json

echo "Connecting to ${MINIO_ENDPOINT}"
mc alias set "${ALIAS}" "${MINIO_ENDPOINT}" "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}" >/dev/null

echo "Ensuring bucket '${MINIO_INVOICE_BUCKET}'"
mc mb --ignore-existing "${ALIAS}/${MINIO_INVOICE_BUCKET}"

# Versioning turns an accidental overwrite into a recoverable event, and gives
# the object-storage part of the lab something more interesting to observe.
echo "Enabling versioning"
mc version enable "${ALIAS}/${MINIO_INVOICE_BUCKET}" >/dev/null

echo "Writing policy '${POLICY_NAME}'"
cat > "${POLICY_FILE}" <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetBucketLocation", "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::${MINIO_INVOICE_BUCKET}"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
      "Resource": ["arn:aws:s3:::${MINIO_INVOICE_BUCKET}/*"]
    }
  ]
}
JSON
mc admin policy create "${ALIAS}" "${POLICY_NAME}" "${POLICY_FILE}" >/dev/null 2>&1 \
  || echo "  policy already exists, leaving it as it is"

echo "Ensuring application user '${MINIO_APP_USER}'"
if mc admin user info "${ALIAS}" "${MINIO_APP_USER}" >/dev/null 2>&1; then
  echo "  user already exists"
else
  mc admin user add "${ALIAS}" "${MINIO_APP_USER}" "${MINIO_APP_PASSWORD}" >/dev/null
fi

echo "Attaching policy to user"
mc admin policy attach "${ALIAS}" "${POLICY_NAME}" --user "${MINIO_APP_USER}" >/dev/null 2>&1 \
  || echo "  policy already attached"

echo
echo "Buckets now present:"
# No pipe through sed here: the mc image is minimal and ships neither sed nor
# awk. Anything added to this script has to hold to that.
mc ls "${ALIAS}"
