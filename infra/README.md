# infra — the AWS half of the deployment

Terraform for the pieces of quicklink that are worth renting rather than running: object
storage and a queue. Everything else (Postgres, Redis, the app instances, the load
balancer) stays in Docker Compose on a single VM.

What this creates:

| Resource | Why |
| --- | --- |
| Two S3 buckets, `originals` and `processed` | The upload pipeline's input and output. Private; the browser only ever sees a 15-minute presigned URL. |
| Lifecycle rules on `originals` | Objects expire after 30 days, and incomplete multipart uploads are aborted after 1 — the two ways object storage quietly grows a bill. |
| An SQS queue plus a dead-letter queue | What `app.queue=sqs` uses instead of RabbitMQ. The redrive policy (`maxReceiveCount = 3`) is the DLX equivalent. |
| An IAM user scoped to exactly those four resources | The app runs off-AWS, so it needs a key. Scope is the mitigation: no `ListBucket`, nothing outside these ARNs. |
| A monthly budget with forecast and actual alerts | The upload endpoint is public and unauthenticated. This is the backstop. |

## Running it

```bash
cd infra
cp terraform.tfvars.example terraform.tfvars   # set budget_notification_email
terraform init
terraform validate
terraform plan
terraform apply
```

`init` and `validate` need nothing but the binary. `plan` and `apply` need credentials
with permission to create the resources above — an admin key in `~/.aws/credentials`,
or `AWS_PROFILE` set. Those are a different, larger credential than the one Terraform
*creates*; the created one is what the application gets.

Then wire the outputs into the application:

```bash
terraform output                                    # every name matches an env var
terraform output -raw app_secret_access_key         # sensitive, so printed on request
```

```bash
APP_QUEUE=sqs
APP_SQS_QUEUE_URL=$(terraform output -raw app_sqs_queue_url)
APP_SQS_DLQ_URL=$(terraform output -raw app_sqs_dlq_url)
APP_S3_ENDPOINT=            # empty: the SDK then talks to real S3, no code change
APP_S3_PUBLIC_ENDPOINT=
APP_S3_BUCKETS_ORIGINALS=$(terraform output -raw app_s3_buckets_originals)
APP_S3_BUCKETS_PROCESSED=$(terraform output -raw app_s3_buckets_processed)
APP_S3_REGION=$(terraform output -raw app_s3_region)
APP_S3_ACCESS_KEY=$(terraform output -raw app_access_key_id)
APP_S3_SECRET_KEY=$(terraform output -raw app_secret_access_key)
APP_SQS_ACCESS_KEY=$APP_S3_ACCESS_KEY
APP_SQS_SECRET_KEY=$APP_S3_SECRET_KEY
```

Teardown is `terraform destroy`. S3 refuses to delete a non-empty bucket, so empty them
first (`aws s3 rm s3://<bucket> --recursive`) — there is no `force_destroy` on these
buckets on purpose, because a stray `destroy` should not silently delete data.

## Deliberately not here

- **Remote state.** State is local, and it contains the IAM secret in plain text, which
  is why it is gitignored. The commented-out S3 backend in `versions.tf` is what a team
  would use; bootstrapping its bucket is a chicken-and-egg chore not worth it for one
  person.
- **Separate identities for app-service and the worker.** One user holds the union of
  both services' permissions. Splitting it is a few lines here and a second set of
  credentials to deploy.
- **An instance role instead of a key.** The right answer on EC2 or ECS, and the
  application already supports it: leave the access keys blank and the SDK's default
  credentials chain picks the role up. It cannot be used from a VM that is not on AWS.
