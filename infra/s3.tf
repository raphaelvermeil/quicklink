# Two buckets, matching app.s3.buckets.originals / .processed:
# app-service writes an upload to originals, the worker reads it, compresses it, and
# writes the result to processed, which is what presigned URLs point at.
#
# Bucket names are globally unique across all of AWS, so the account id is appended
# rather than asking for a name nobody can guess in advance.

data "aws_caller_identity" "current" {}

locals {
  suffix         = data.aws_caller_identity.current.account_id
  originals_name = "${var.project}-originals-${local.suffix}"
  processed_name = "${var.project}-processed-${local.suffix}"
}

resource "aws_s3_bucket" "originals" {
  bucket = local.originals_name
}

resource "aws_s3_bucket" "processed" {
  bucket = local.processed_name
}

# Nothing here is served directly from S3: the browser only ever gets a presigned URL
# with a 15 minute expiry. So both buckets stay fully private, and these four flags
# make that explicit rather than relying on the account default.
resource "aws_s3_bucket_public_access_block" "buckets" {
  for_each = {
    originals = aws_s3_bucket.originals.id
    processed = aws_s3_bucket.processed.id
  }

  bucket                  = each.value
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "buckets" {
  for_each = {
    originals = aws_s3_bucket.originals.id
    processed = aws_s3_bucket.processed.id
  }

  bucket = each.value

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Cost control, which on object storage is almost entirely about what you DON'T keep.
resource "aws_s3_bucket_lifecycle_configuration" "originals" {
  bucket = aws_s3_bucket.originals.id

  rule {
    id     = "expire-originals"
    status = "Enabled"

    filter {}

    expiration {
      days = var.original_retention_days
    }
  }

  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"

    filter {}

    # An interrupted multipart upload leaves parts behind that are invisible in the
    # console and billed forever. Everyone learns this from a bill.
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}
