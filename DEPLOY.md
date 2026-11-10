# Deploying quicklink

One VM running Docker Compose, with S3 and SQS rented from AWS. The reasoning behind that
split is in the README; this is the checklist.

Expected cost: **$0/month for the VM** (Oracle Cloud's always-free tier) and **cents for
AWS** — SQS gives a million requests a month permanently free, and the only real charge is
S3 storage at $0.023/GB. Step 1 sets up a budget alarm before anything else exists.

---

## 0. What you need

- An **AWS account** with a payment method (the free tier still requires one).
- An **Oracle Cloud account** for the free VM. Also needs a card for identity
  verification; always-free resources are not charged.
- **Terraform ≥ 1.6** locally: <https://developer.hashicorp.com/terraform/install>
- An **SSH key pair** (`ssh-keygen -t ed25519` if you don't have one).

Nothing here needs a domain name. Step 4 is where you'd add one.

---

## 1. AWS: create the buckets and the queue

**1.1 — Make credentials for Terraform.** Don't use the root user. In the AWS console:
IAM → Users → Create user → attach `AdministratorAccess` → Security credentials → Create
access key → "Command Line Interface". Then locally:

```bash
aws configure            # or write ~/.aws/credentials by hand
```

This key is for *you*, to run Terraform. It is not the key the application gets — Terraform
creates a second, tightly scoped one for that.

**1.2 — Apply.**

```bash
cd infra
cp terraform.tfvars.example terraform.tfvars
$EDITOR terraform.tfvars          # set budget_notification_email
terraform init
terraform apply                   # review the plan, then type yes
```

Roughly a dozen resources: two buckets with lifecycle rules, the queue and its dead-letter
queue, an IAM user scoped to those four ARNs, and the budget alarm.

**1.3 — Confirm the budget email.** AWS sends a subscription confirmation. Click it. An
alarm you never confirmed is not an alarm.

**1.4 — Keep the outputs.** You need them in step 3:

```bash
terraform output
terraform output -raw app_secret_access_key
```

---

## 2. The VM

**2.1 — Create the instance.** Oracle Cloud console → Compute → Instances → Create.

| Setting | Value |
| --- | --- |
| Image | Canonical Ubuntu 24.04 |
| Shape | **Ampere A1 Flex** (ARM), 2 OCPU / 12 GB — within the always-free allowance of 4 OCPU / 24 GB |
| SSH key | paste your public key |
| Networking | assign a public IPv4 |

> **If you get "Out of host capacity"**, that is the usual experience with the free ARM
> shape — it is genuinely oversubscribed. Retry over a few hours, or try another
> availability domain in the same region. The free x86 shape (1 GB RAM) is too small for
> this stack; a €4/month Hetzner ARM box is the honest fallback.

The ARM part costs nothing in the code: the images are built on the VM itself, and
`eclipse-temurin:21` is multi-arch.

**2.2 — Open port 80, in both places.** This is the step that wastes everyone's afternoon.
Oracle filters traffic twice: in the VCN security list *and* in iptables rules baked into
their Ubuntu image.

In the console: VCN → Subnet → Security List → Add ingress rule, source `0.0.0.0/0`,
TCP destination port 80.

Then on the VM:

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo netfilter-persistent save      # or it is gone on the next reboot
```

**2.3 — Install Docker.**

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
exit                                 # log back in for the group to take effect
```

---

## 3. Deploy

**3.1 — Clone, into a directory named `quicklink`.** The load balancer addresses the app
instances by container name (`quicklink-app-1`), and Compose derives that prefix from the
directory name.

```bash
git clone https://github.com/<you>/quicklink.git ~/quicklink
cd ~/quicklink
```

**3.2 — Write `.env`** next to `docker-compose.cloud.yml`, filling in the Terraform outputs
from step 1.4. It is gitignored; it must never be committed.

```bash
cat > .env <<'EOF'
POSTGRES_PASSWORD=<a long random string>
APP_BASE_URL=http://<the VM's public IP>
AWS_REGION=eu-west-3
AWS_ACCESS_KEY_ID=<app_access_key_id>
AWS_SECRET_ACCESS_KEY=<app_secret_access_key>
APP_SQS_QUEUE_URL=<app_sqs_queue_url>
APP_SQS_DLQ_URL=<app_sqs_dlq_url>
APP_S3_BUCKETS_ORIGINALS=<app_s3_buckets_originals>
APP_S3_BUCKETS_PROCESSED=<app_s3_buckets_processed>
EOF
chmod 600 .env
```

`APP_BASE_URL` is what every short link will contain, so it has to be the address other
people will use, not `localhost`.

**3.3 — Start it.**

```bash
docker compose -f docker-compose.cloud.yml up -d --build
```

The first build compiles all three modules and takes several minutes. Compose refuses to
start if any variable above is missing, naming the one it wants.

**3.4 — Verify, in order.** Each step exercises one more layer.

```bash
# the load balancer is reaching an app instance
curl -i http://<ip>/actuator/health          # 200, and an X-Instance-Id header

# Postgres, Redis and the rate limiter
curl -i -X POST http://<ip>/api/links \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com"}'     # 201, X-Rate-Limit-Remaining: 9

curl -i http://<ip>/<shortCode>              # 302 to example.com

# S3 and SQS end to end: upload, then watch the status flip
curl -i -F file=@some-photo.jpg http://<ip>/api/media        # 202, status PENDING
curl    http://<ip>/api/media/<shortCode>/status             # READY, with byte counts
```

If the upload sticks at `PENDING`, the worker is the place to look:

```bash
docker compose -f docker-compose.cloud.yml logs -f worker
curl http://<ip>/api/inspect/queues          # depth, or an error if the queue is unreachable
```

The visualiser is at `http://<ip>/`.

---

## 4. Optional: a domain and HTTPS

Presigned S3 URLs are already HTTPS; it is the app's own traffic that is plain HTTP, which
means a short link is visible to anyone on the path. With a domain pointed at the VM, Caddy
gets a certificate on its own:

1. Point an A record at the VM's IP.
2. Open port 443 in the security list and in iptables, as in step 2.2.
3. Add a `caddy` service in front of `edge-lb`, with a two-line Caddyfile
   (`your.domain { reverse_proxy edge-lb:8080 }`), and take the published port off
   `edge-lb` so Caddy is the only thing listening.
4. Set `APP_BASE_URL=https://your.domain` and recreate the app instances.

---

## 5. Operating it

```bash
# logs
docker compose -f docker-compose.cloud.yml logs -f app
docker compose -f docker-compose.cloud.yml logs -f worker

# deploy a new version
git pull && docker compose -f docker-compose.cloud.yml up -d --build

# kill one instance and watch the balancer route around it
docker stop quicklink-app-2
```

**Watch the bill, not just the alarm.** The upload endpoint is public and unauthenticated:
anyone who finds it can put objects in the originals bucket. The lifecycle rule expires them
after 30 days and the budget alarm fires at a forecast dollar, but if this is going to stay
up, put something in front of the upload endpoint.

**Teardown.**

```bash
aws s3 rm s3://<originals-bucket> --recursive
aws s3 rm s3://<processed-bucket> --recursive
cd infra && terraform destroy
```

Then terminate the VM in the Oracle console. Terraform will not delete a non-empty bucket,
on purpose.
