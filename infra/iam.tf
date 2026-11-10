# Credentials for an application that does NOT run on AWS.
#
# If this ran on EC2 or ECS it would get an instance role and there would be no
# long-lived key anywhere — which is why S3Config falls through to the SDK's default
# credentials chain when no key is configured. It runs on a free VM elsewhere, so a
# key has to exist, and the mitigation is scope: this user can touch these two buckets
# and these two queues and nothing else in the account.
#
# The honest limitation: one user shared by app-service and the worker, so each holds
# slightly more permission than it needs. Splitting them is a five-line change here and
# a second set of credentials to deploy.

resource "aws_iam_user" "app" {
  name = "${var.project}-app"
}

resource "aws_iam_access_key" "app" {
  user = aws_iam_user.app.name
}

data "aws_iam_policy_document" "app" {
  # Object-level only. No ListBucket: neither service enumerates a bucket, and leaving
  # it out means a leaked key cannot be used to inventory what is stored.
  statement {
    sid    = "ReadWriteObjects"
    effect = "Allow"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
    ]
    resources = [
      "${aws_s3_bucket.originals.arn}/*",
      "${aws_s3_bucket.processed.arn}/*",
    ]
  }

  # SendMessage is app-service; the three receive-side actions are the worker.
  # GetQueueAttributes is what lets the inspect endpoint report queue depth.
  statement {
    sid    = "UseMediaQueues"
    effect = "Allow"
    actions = [
      "sqs:SendMessage",
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes",
    ]
    resources = [
      aws_sqs_queue.media_compress.arn,
      aws_sqs_queue.media_compress_dlq.arn,
    ]
  }
}

resource "aws_iam_user_policy" "app" {
  name   = "${var.project}-app"
  user   = aws_iam_user.app.name
  policy = data.aws_iam_policy_document.app.json
}
