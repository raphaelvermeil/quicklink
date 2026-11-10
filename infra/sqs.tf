# The queue app.queue=sqs uses, plus its dead-letter queue.
#
# Worth comparing with RabbitConfig: on Rabbit the application declares the queue, a
# dead-letter exchange and a binding at startup, and must reject without requeue for
# dead-lettering to happen at all. Here the queue is infrastructure, the redrive policy
# belongs to the queue rather than the consumer, and the only thing the consumer does
# is fail to delete the message.

resource "aws_sqs_queue" "media_compress_dlq" {
  name = "${var.project}-media-compress-dlq"

  # Two weeks: long enough to look at a failure after a weekend.
  message_retention_seconds = 1209600
}

resource "aws_sqs_queue" "media_compress" {
  name = "${var.project}-media-compress"

  # Must exceed the slowest compression, or SQS hands the same job to a second worker
  # while the first is still on it. The worker's READY check makes that harmless rather
  # than destructive, but it still wastes the work.
  visibility_timeout_seconds = 120

  # Matches app.sqs.wait-seconds: the consumer long-polls, so an idle worker makes one
  # request every 20 seconds instead of hammering the API.
  receive_wait_time_seconds = 20

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.media_compress_dlq.arn
    maxReceiveCount     = 3
  })
}

# Says who may use that DLQ as a redrive target. Without it the DLQ would accept
# redrives from any queue in the account.
resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  queue_url = aws_sqs_queue.media_compress_dlq.id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.media_compress.arn]
  })
}
