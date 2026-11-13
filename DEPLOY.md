# Deploying quicklink

One VM running Docker Compose, with S3 and SQS rented from AWS. The reasoning behind that
split is in the README; this is the checklist.

Expected cost: **about $25/month for the VM**, paid out of Azure for Students' $100 credit
— roughly three months of running it 24/7 — and **cents for AWS**: SQS gives a million
requests a month permanently free, and the only real charge is S3 storage at $0.023/GB.
Step 1 sets up a budget alarm before anything else exists.

> **Why not Oracle Cloud's Always Free tier,** which this guide used to specify: that shape
> has no capacity. Over 1,300 automated create attempts in the home region's single
> availability domain returned `Out of host capacity` at 2 OCPU / 12 GB, 1 / 6 and 1 / 4.
> Oracle also cut the Always Free A1 allowance from 4 OCPU / 24 GB to 2 / 12 in June 2026.
> Azure for Students costs credit rather than nothing, but it can actually be provisioned.

---

## 0. What you need

- An **AWS account** with a payment method (the free tier still requires one).
- An **Azure for Students subscription** for the VM: $100 of credit, valid 12 months and
  renewable while enrolled, no card required. Sign up with a university email.
- The **AWS CLI**, which step 1.1 and the teardown both need. On macOS use AWS's official
  `.pkg` installer, not Homebrew's `awscli` — that build currently crashes on an
  expat/pyexpat symbol mismatch.
- **Terraform ≥ 1.6**: `brew tap hashicorp/tap && brew install hashicorp/tap/terraform`.
  Homebrew core no longer carries a current Terraform, since it stopped being open source.
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

**1.3 — Check the budget landed.** Billing → Budgets → `quicklink-monthly`. There is
nothing to confirm: the budget emails the address directly, and the confirmation click
people remember belongs to budgets that notify through an SNS topic instead.

**1.4 — Keep the outputs.** You need them in step 3:

```bash
terraform output
terraform output -raw app_secret_access_key
```

---

## 2. The VM

**2.1 — Create the instance.** Azure portal → Virtual machines → Create → Azure virtual
machine, into a new resource group named `quicklink-rg`.

| Setting | Value |
| --- | --- |
| Subscription | Azure for Students |
| Region | **North Central US** — one of the five this subscription allows, and the closest to the AWS resources in `us-east-2` |
| Image | Ubuntu Server 24.04 LTS, **Arm64** |
| Size | **Standard_B2pls_v2** — 2 vCPU, 4 GB, ARM, $0.0336/hr (about $24.50/month) |
| Availability | No infrastructure redundancy required |
| Authentication | SSH public key, username `azureuser` |
| Inbound ports | 22 and 80 |
| OS disk | Standard SSD, default size (about 30 GB) |

Four things about student subscriptions that will otherwise cost you an afternoon:

- **Regions are restricted.** Check Policy → Assignments → *Allowed resource deployment
  regions* before you pick one; anything else fails at validation.
- **The size dropdown lies.** It shows only the image publisher's suggestions, which
  exclude `B2pls_v2`. Click **See all sizes**.
- **Pick no redundancy,** not an availability zone — zonal capacity for ARM burstable
  sizes is frequently unavailable on these subscriptions.
- **Confirm the NIC network security group is Basic** with 22 and 80 allowed. That NSG is
  the only firewall in the path: unlike Oracle's image, Azure's Ubuntu ships no iptables
  rules of its own, so there is no second place to open the port.

4 GB is the floor, not a preference — the six JVMs, Postgres and Redis sit at about 1.7 GB,
and the first `--build` runs Gradle on top of that. The architecture being ARM costs
nothing in the code: images are built on the VM and `eclipse-temurin:21` is multi-arch.

**2.2 — Install Docker.**

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
exit                                 # log back in for the group to take effect
```

**2.3 — Add swap,** or the first build will be OOM-killed partway through compiling.

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab   # survives a reboot
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
AWS_REGION=us-east-2
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
people will use, not `localhost`. Take `AWS_REGION` from `terraform output app_s3_region`
rather than typing it, so the two cannot drift apart.

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
2. Add an inbound rule for TCP 443 to the VM's network security group.
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

**Watch the credit, not just the AWS bill.** The VM is the expensive half now. Running
24/7 the $100 credit lasts about three months once the disk and public IP are counted;
when it runs out Azure disables the subscription rather than billing you, so the
deployment stops instead of costing money. To stretch it, Stop (deallocate) the VM from
the portal when nobody is looking at it — compute stops being charged, the disk does not.
Check in the portal that the public IP is **static** before you do that the first time: a
dynamic one is released on deallocation, and every short link already issued points at the
old address.

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

Then delete the `quicklink-rg` resource group in the Azure portal, which takes the VM,
its disk, its public IP and the NSG with it. Terraform will not delete a non-empty bucket,
on purpose.
