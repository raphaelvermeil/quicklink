# These map one-to-one onto the application's environment variables, which is the whole
# point: `terraform output` produces the deployment's configuration rather than a
# human transcribing names from the console.

output "app_s3_buckets_originals" {
  description = "APP_S3_BUCKETS_ORIGINALS"
  value       = aws_s3_bucket.originals.id
}

output "app_s3_buckets_processed" {
  description = "APP_S3_BUCKETS_PROCESSED"
  value       = aws_s3_bucket.processed.id
}

output "app_s3_region" {
  description = "APP_S3_REGION and APP_SQS_REGION"
  value       = var.aws_region
}

output "app_sqs_queue_url" {
  description = "APP_SQS_QUEUE_URL"
  value       = aws_sqs_queue.media_compress.url
}

output "app_sqs_dlq_url" {
  description = "APP_SQS_DLQ_URL"
  value       = aws_sqs_queue.media_compress_dlq.url
}

output "app_access_key_id" {
  description = "APP_S3_ACCESS_KEY and APP_SQS_ACCESS_KEY"
  value       = aws_iam_access_key.app.id
}

output "app_secret_access_key" {
  description = <<-EOT
    APP_S3_SECRET_KEY and APP_SQS_SECRET_KEY. Marked sensitive so it is not printed by
    accident — read it with `terraform output -raw app_secret_access_key`.

    Note what sensitive does NOT do: the secret is still stored in plain text in
    terraform.tfstate, which is why that file is gitignored and why a real deployment
    keeps state in an encrypted remote backend.
  EOT
  value       = aws_iam_access_key.app.secret
  sensitive   = true
}
