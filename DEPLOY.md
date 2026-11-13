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

You also need a **DNS name pointing at the VM**. HTTPS is not optional in this stack — it
will not start without `DOMAIN`, and it cannot be reached by bare IP. If you don't own a
domain, Azure gives every public IP a free `<label>.<region>.cloudapp.azure.com` name that
Let's Encrypt is happy to certify. Step 4 covers it.

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
DOMAIN=<your.domain>
APP_BASE_URL=https://<your.domain>
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

Nothing answers over HTTPS yet, and that is expected: Caddy needs the DNS record from step
4.1 before Let's Encrypt will issue a certificate. It keeps retrying on its own, so starting
now and pointing DNS afterwards is fine — the certificate appears once the name resolves.
**Step 5 is where you check that any of this works**, because every URL worth testing is an
`https://` one.

---

## 4. The domain and HTTPS

**This is not an optional step any more.** `caddy` publishes 80 and 443, `edge-lb` publishes
nothing, and `DOMAIN` has no default — the stack refuses to start without it, and there is no
way to reach it by bare IP. Presigned S3 URLs were always HTTPS; this is what covers the
app's own traffic, including the short links themselves.

**4.1 — Point DNS at the VM.** An A record for your hostname at the VM's public IPv4.
Let's Encrypt validates over HTTP against that name, so the record has to resolve *before*
the stack starts. Confirm the VM's public IP is **Static** first (step 6) or the record goes
stale the first time you deallocate.

**4.2 — Open 443.** An inbound rule for TCP 443 in the VM's network security group, next to
the 22 and 80 from step 2.1. Port 80 has to stay open: Caddy answers the ACME HTTP challenge
on it and redirects `http://` to `https://`.

**4.3 — Set both variables** in `.env`:

```bash
DOMAIN=quicklink.example.com
APP_BASE_URL=https://quicklink.example.com
```

Two variables rather than one, and they have to agree. `DOMAIN` is the certificate and the
only hostname Caddy will answer to; `APP_BASE_URL` is baked into every short link the API
hands out, so getting it wrong produces links that resolve but point elsewhere.

**4.4 — Watch the certificate arrive.** The stack is already running from step 3.3; if you
changed `.env` to add `DOMAIN`, re-run that command first so Caddy picks it up.

```bash
docker compose -f docker-compose.cloud.yml logs -f caddy

curl -I https://<your.domain>/actuator/health     # 200
curl -I http://<your.domain>/                     # 308 to https://
```

If the certificate never appears, it is almost always DNS not yet pointing at the VM, or 80
closed in the NSG; Caddy's log names which. Certificates live in the `caddy_data` volume, so
restarts reuse them — Let's Encrypt permits 5 duplicate certificates per week, which is
easy to burn through while debugging something unrelated.

**The rate limiter survives the extra hop.** Every request now reaches `edge-lb` from Caddy's
container address, so a limiter keying on the socket would put all callers in one bucket.
[`Caddyfile`](Caddyfile) documents why it doesn't: Caddy declares no `trusted_proxies`, so it
discards any `X-Forwarded-For` the caller sent and writes the real peer; `edge-lb` appends to
that; the app's `ForwardedHeaderFilter` reads the first entry. Verified by creating a link
through Caddy and reading the bucket back — the key was the caller's own address, and a
request carrying `X-Forwarded-For: 9.9.9.9` produced no bucket of its own.

---

## 5. Verify, in order

Each step exercises one more layer. Everything is `https://` now, so these only pass once
step 4 has finished and Caddy holds a certificate.

```bash
# TLS, Caddy, the load balancer, and an app instance behind it
curl -i https://<your.domain>/actuator/health      # 200, and an X-Instance-Id header

# Postgres, Redis and the rate limiter
curl -i -X POST https://<your.domain>/api/links \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com"}'           # 201, X-Rate-Limit-Remaining: 9

curl -i https://<your.domain>/<shortCode>          # 302 to example.com

# S3 and SQS end to end: upload, then watch the status flip
curl -i -F file=@some-photo.jpg https://<your.domain>/api/media    # 202, status PENDING
curl    https://<your.domain>/api/media/<shortCode>/status         # READY, with byte counts
```

The `shortUrl` in that 201 should start with `https://<your.domain>`. If it says something
else, `APP_BASE_URL` disagrees with `DOMAIN` — see step 4.3, and note that links already
created keep the old address.

If the upload sticks at `PENDING`, the worker is the place to look:

```bash
docker compose -f docker-compose.cloud.yml logs -f worker
curl https://<your.domain>/api/inspect/queues      # depth, or an error if the queue is unreachable
```

A worker that exited instead of staying up would show as `Restarting` here:

```bash
docker compose -f docker-compose.cloud.yml ps
```

The visualiser is at `https://<your.domain>/`.

---

## 6. Operating it

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
