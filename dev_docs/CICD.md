# CI/CD: Auto-deploy to EC2 on `git push`

A complete walkthrough of the deployment pipeline for the Codex Execution Engine —
what it does, **why each decision was made**, how to set it up, and how to talk
about it in an interview.

---

## Part 1 — The problem we're solving

### What you do today (manually)

```bash
ssh -i key.pem ubuntu@3.110.161.110
cd ~/codex-execution
git pull
sudo docker compose -f docker-compose.ec2.yml --env-file .env up -d --build
```

That works, but it has five real problems:

| Problem | Why it hurts |
|---|---|
| **It's manual** | You must be at your laptop with the `.pem` key. Easy to forget a step. |
| **It builds on the EC2 box** | `--build` runs the whole Maven compile **inside Docker on a 1 GB t2.micro**. |
| **Nothing is tested first** | A broken commit deploys straight to production. |
| **No rollback** | If the new build is broken, you're SSH-ing in at 2am to fix it. |
| **No record** | Nobody knows what's actually running, or who deployed it. |

### The single most important insight: **don't build on the target**

This is the thing to understand, and the thing interviewers listen for.

Look at the `Dockerfile` — it's a **multi-stage build**:

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS builder   # ← stage 1: compile
RUN mvn package -DskipTests -B

FROM eclipse-temurin:17-jre-jammy              # ← stage 2: just run the JAR
COPY --from=builder /build/target/executor-agent.jar app.jar
```

When you run `docker compose up --build` **on the EC2**, *stage 1 runs on the EC2*.
That means the t2.micro must:
- download the entire Maven dependency tree (hundreds of MB),
- run `javac` and package a fat JAR,
- all on **1 vCPU and 1 GB of RAM** (backed by a 2 GB swapfile).

Consequences: builds take many minutes, the box thrashes into swap, you risk an
**OOM kill**, and the agent is **down or degraded the whole time**.

**The fix:** the *build* and the *run* are different jobs with different resource
needs. Build on a machine that's good at building (GitHub's runner: 4 vCPU, 16 GB,
free), produce an immutable artifact (a Docker image), push it to a registry, and
let the tiny EC2 do the only thing it's good at — **pull the finished image and
run it**. EC2 deploy time drops from *minutes of compiling* to *seconds of pulling*.

> **Interview soundbite:** *"The deployment target was a 1 GB t2.micro, so I moved
> the build off it. CI builds the image on GitHub's runner and pushes to GHCR; the
> EC2 only pulls and restarts. That turned a multi-minute, OOM-prone, downtime-y
> build into a few-second pull, and it made deploys reproducible."*

---

## Part 2 — CI vs CD (get the vocabulary right)

Interviewers *will* poke at this.

- **CI — Continuous Integration.** Every push is automatically **built and tested**.
  The point is to catch breakage early, on every commit, instead of at merge time.
  *Our CI = the `test` job (`mvn test`) + the image build.*

- **CD — Continuous Delivery.** Every commit that passes CI is automatically turned
  into a **deployable artifact**, ready to ship — but a **human clicks deploy**.

- **CD — Continuous Deployment.** Same, but there's **no human**. Passing CI goes
  straight to production.

**Our pipeline is Continuous Deployment**: push to `main` → tests → image → live on
EC2, no human. That's the right call for a solo project. On a team with real users
you'd often stop at Continuous *Delivery* and gate production behind an approval.

> If asked *"which do you have?"*, the strong answer names it precisely **and says
> why it's appropriate here**, plus what you'd change at scale (approval gate,
> staging environment, canary).

---

## Part 3 — The pipeline, step by step

```
  git push origin main
          │
          ▼
  ┌──────────────────────────────────────────────┐
  │ GitHub Actions runner (4 vCPU, 16 GB, free)  │
  │                                              │
  │  job 1: test         mvn test                │  ← CI gate. Fails → stop.
  │           │                                  │
  │           ▼                                  │
  │  job 2: build-and-push                       │
  │           docker build (multi-stage)         │
  │           push ghcr.io/…:a1b2c3d  ← SHA tag  │
  │           push ghcr.io/…:latest              │
  └───────────┬──────────────────────────────────┘
              │ ssh
              ▼
  ┌──────────────────────────────────────────────┐
  │ EC2 t2.micro (1 GB)                          │
  │  job 3: deploy                               │
  │     git reset --hard origin/main   (compose) │
  │     docker compose pull   ← finished image   │
  │     docker compose up -d  ← restart          │
  │     curl /v1/healthz      ← health gate      │
  │     ✅ keep  /  ❌ roll back to previous tag │
  └──────────────────────────────────────────────┘
```

### File: `.github/workflows/deploy.yml`

#### Trigger + path filter

```yaml
on:
  push:
    branches: [main]
    paths:
      - 'src/**'
      - 'pom.xml'
      - 'Dockerfile'
      - 'docker-compose.ec2.yml'
  workflow_dispatch: {}
```

- **`branches: [main]`** — feature branches build nothing. Only `main` is production.
- **`paths:`** — a README edit should **not** restart production. This filter means the
  pipeline only fires when something that actually affects the running agent changes.
- **`workflow_dispatch`** — adds a manual **"Run workflow"** button in the GitHub UI.
  Essential: it's how you re-deploy or roll back without pushing an empty commit.

#### Concurrency

```yaml
concurrency:
  group: ec2-deploy
  cancel-in-progress: false
```

If you push twice quickly, two deploys could otherwise SSH into the box **at the
same time** and fight over the container. `group` serialises them.

`cancel-in-progress: false` is a **deliberate** choice. For a *test* job you'd
cancel the stale run to save time. For a *deploy* you must not — cancelling
mid-`docker compose up` can leave the container stopped. **Queue, don't cancel.**

> **Interview question:** *"Why not `cancel-in-progress: true`?"* — Because a
> half-cancelled deployment leaves the system in an undefined state. Cancellation is
> safe for pure-compute jobs, dangerous for jobs with side effects.

#### Job 1 — the CI gate

```yaml
  test:
    steps:
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          cache: maven      # ← caches ~/.m2 across runs
      - run: mvn -B --no-transfer-progress test
```

`cache: maven` persists the `~/.m2` dependency cache between runs, so you don't
re-download the internet every push. `-B` = batch mode (no interactive prompts,
cleaner logs).

Every later job has `needs: test`, so **a failing test blocks the deploy entirely.**
That is the whole point of CI.

#### Job 2 — build once, tag immutably

```yaml
      - name: Compute image name and immutable tag
        run: |
          owner="${GITHUB_REPOSITORY_OWNER,,}"     # lowercase — registries require it
          echo "image=ghcr.io/${owner}/codex-executor-agent" >> "$GITHUB_OUTPUT"
          echo "tag=${GITHUB_SHA::7}" >> "$GITHUB_OUTPUT"
```

Two subtle but important things:

**1. Lowercase.** Your GitHub owner is `Rocky4554`, but **Docker registry paths must
be lowercase**. `${VAR,,}` is bash lowercasing. Forgetting this is a classic
"works-on-my-machine" CI failure.

**2. Tag with the commit SHA, not just `latest`.** This is a real best practice:

| | `:latest` only | `:a1b2c3d` (SHA) |
|---|---|---|
| What's running in prod? | Nobody knows | Exactly commit `a1b2c3d` |
| Roll back | Impossible — `latest` moved | `docker compose up` with the old tag |
| Reproducible | No | Yes |

We push **both**: the SHA tag (immutable, for pinning/rollback) and `latest`
(convenient default).

> **Interview question:** *"Why is deploying `:latest` an anti-pattern?"* — Because
> it's a **mutable** pointer. Two servers pulling `:latest` an hour apart can run
> different code. You can't roll back, can't reproduce a bug, and can't answer
> "what's in prod?" Immutable, content-addressed tags fix all three.

**Authentication:** we push to GHCR using `secrets.GITHUB_TOKEN` — a token GitHub
**injects automatically**. No secret to create, and it's scoped to this repo and
expires with the job. We just grant it `packages: write`.

**Caching:** `cache-from: type=gha` / `cache-to: type=gha,mode=max` stores Docker
**layer** cache in GitHub's cache. Because the Dockerfile copies `pom.xml` and runs
`mvn dependency:go-offline` *before* copying `src/`, the dependency layer is reused
whenever only source changed. That's why the Dockerfile is written that way —
**layer ordering is a caching strategy.**

#### Job 3 — deploy (and the safety net)

```bash
PREV_IMAGE="$(docker inspect -f '{{.Config.Image}}' codex-executor-agent || true)"

deploy_tag() {
  IMAGE_TAG="$1" docker compose -f docker-compose.ec2.yml --env-file .env pull
  IMAGE_TAG="$1" docker compose -f docker-compose.ec2.yml --env-file .env up -d
}

deploy_tag "${TAG}"

if curl -fsS --retry 20 --retry-delay 3 --retry-all-errors http://localhost:8081/v1/healthz; then
  echo "✅ Healthy"; docker image prune -f
else
  echo "❌ rolling back"; deploy_tag "${PREV_IMAGE##*:}"; exit 1
fi
```

The important parts:

- **Record `PREV_IMAGE` *before* deploying.** You cannot roll back to a version you
  didn't write down.
- **The health gate.** `docker compose up -d` returning success only means *the
  container started*. It does **not** mean the app works — a JVM can start and then
  throw on a bad config. So we **prove** it serves traffic by curling
  `/v1/healthz`. `--retry 20 --retry-delay 3` gives the JVM up to 60s to boot.
  **"Started" ≠ "healthy"** is a distinction interviewers love.
- **Automatic rollback.** If the health gate fails, we redeploy the previous tag.
  This is why immutable SHA tags matter — rollback is just "deploy the old tag".
- **`exit 1`.** Even after a successful rollback we **fail the workflow**, so you get
  a red ❌ and an email. A silent auto-heal that nobody notices is a trap.
- **`docker image prune -f`.** The EBS volume is small; old images accumulate and
  eventually fill the disk. Housekeeping is part of deployment.

#### Why `git reset --hard origin/main` on the box?

The **image** carries the app, but the **compose file** lives on disk. If you change
`docker-compose.ec2.yml`, EC2 needs it. `git pull` can fail ("local changes would be
overwritten"); `reset --hard` guarantees the box matches `main` exactly.

It's safe for `.env` because **`.env` is untracked** — `reset --hard` only touches
tracked files. (`git clean -fd` *would* delete it. Don't add that.)

---

## Part 4 — The second workflow: sandbox images

`.github/workflows/executor-images.yml` exists because the language sandbox images
(`codex-cpp`, `codex-python`, `codex-sql`, …) are **fundamentally different** from
the agent image:

- The agent image is **pulled from a registry** by Docker Compose.
- The sandbox images are looked up **by name in the EC2 daemon's local image store**
  (the backend's `languages` table literally says `codex-sql:latest`). They are
  never pulled — they must be **built on the box**.

So this workflow SSHes in and runs `./docker/executors/build-all.sh`. It triggers
**only** on `paths: docker/executors/**`, because these change rarely and rebuilding
the C++ precompiled header is slow. It shares the `ec2-deploy` concurrency group so
it can't collide with an agent deploy.

> Docker's layer cache means rebuilding "all" images is cheap when only one
> Dockerfile changed — unchanged images hit the cache and finish instantly.

This is also how your **new `codex-sql` image** ships automatically.

---

## Part 5 — Setup (do this once)

### 1. Add the GitHub secrets

Repo → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `EC2_HOST` | `3.110.161.110` |
| `EC2_USER` | `ubuntu` |
| `EC2_SSH_KEY` | The **entire contents** of `code_execution_engine.pem`, including the `-----BEGIN…` and `-----END…` lines |

> You do **not** need a secret for GHCR — `GITHUB_TOKEN` is injected automatically.

**Never commit the `.pem`.** Secrets are encrypted at rest and masked in logs (if a
secret's value would be printed, GitHub replaces it with `***`).

### 2. Make the GHCR package pullable by EC2

The first push creates a GHCR package. By default it's **private**, and your EC2
isn't logged in, so `docker compose pull` would 401.

**Simplest:** make it public — GitHub → your profile → **Packages** →
`codex-executor-agent` → **Package settings** → **Change visibility → Public**.
(The image contains only your compiled agent; no secrets are baked in.)

**If it must stay private:** create a PAT with `read:packages`, then once on EC2:
```bash
echo "$PAT" | docker login ghcr.io -u Rocky4554 --password-stdin
```
Docker stores the credential, so future pulls work.

### 3. Let GitHub's runner reach your EC2 on port 22

GitHub-hosted runners have **dynamic IPs from a huge range**, so you cannot simply
allowlist one IP. Your options, worst → best:

| Approach | Trade-off |
|---|---|
| **SG allows `0.0.0.0/0` on port 22** | Works instantly. But your SSH port is exposed to the whole internet. Key-only auth (no passwords) makes it survivable — this is what most small projects do. |
| **Open the SG just-in-time** | The workflow calls the AWS API to add the runner's IP to the SG, deploys, then removes it. Needs AWS credentials in CI. |
| **AWS SSM Session Manager** ⭐ | **Close port 22 entirely.** An SSM agent on the EC2 makes an *outbound* connection to AWS; CI sends the command through the AWS API with IAM auth. No inbound ports, no SSH key in GitHub, full CloudTrail audit log. |

> **Interview gold:** if you're asked *"how do you SSH from CI securely?"*, the
> answer that separates senior from junior is: **"Ideally I don't. GitHub runners
> have dynamic IPs, so allowlisting doesn't work and you end up exposing port 22.
> I'd use AWS SSM Session Manager — the instance dials out, so there's no inbound
> port at all, access is IAM-controlled and audited in CloudTrail, and there's no
> long-lived SSH key sitting in GitHub secrets."**
>
> Then add the second half: **"And I'd replace static AWS keys with OIDC — GitHub
> Actions can federate into an IAM role using a short-lived token, so there are no
> permanent cloud credentials stored in the repo at all."**

### 4. Push

```bash
git add .github docker-compose.ec2.yml
git commit -m "Add CI/CD pipeline"
git push origin main
```

Watch it under the repo's **Actions** tab.

---

## Part 6 — How to roll back

**Automatic:** if the health check fails, the pipeline already rolled you back.

**Manual (a bad commit that *is* healthy but wrong):**
```bash
ssh -i key.pem ubuntu@3.110.161.110
cd ~/codex-execution
IMAGE_TAG=<older-7-char-sha> docker compose -f docker-compose.ec2.yml --env-file .env up -d
```
That's the entire payoff of immutable SHA tags — rollback is one command, and it
deploys **exactly** the bytes that were running before.

**Or:** `git revert` the bad commit and push. The pipeline redeploys the good state.

---

## Part 7 — Interview prep

### The 60-second story

> *"The execution engine runs on a 1 GB EC2 t2.micro. Deploys were manual — SSH in,
> git pull, `docker compose up --build`. That build ran Maven **on** the t2.micro,
> which took minutes, thrashed swap, risked an OOM kill, and left the service
> degraded the whole time.*
>
> *I built a GitHub Actions pipeline that separates build from run. On a push to
> main, the runner runs the tests, builds the Docker image, and pushes it to GHCR
> tagged with the commit SHA. Then it SSHes to the EC2, which just pulls the
> finished image and restarts — seconds, no compilation. It health-checks
> `/v1/healthz` afterwards and **automatically rolls back to the previous SHA** if the
> new version doesn't come up healthy.*
>
> *The key ideas are: build once on capable hardware, ship an immutable artifact,
> never trust 'the container started' as 'the app works', and always keep a
> reversible path."*

### Rapid-fire Q&A

**Q: What's the difference between CI, Continuous Delivery, and Continuous Deployment?**
CI = auto build + test every commit. Continuous **Delivery** = every green commit
produces a deployable artifact, but a human approves the release. Continuous
**Deployment** = no human; green means live. *Mine is Continuous Deployment, which
suits a solo project; with real users I'd add an approval gate and a staging env.*

**Q: Why not just build the image on the server?**
Build and run have opposite resource profiles. Building needs lots of CPU/RAM
transiently; running needs a little, constantly. The t2.micro is sized for *running*.
Building there is slow, OOM-prone, and takes the service down. Also, building on
each server means each server could produce a *different* image — **build once,
deploy the same artifact everywhere** is the principle.

**Q: Why tag with the commit SHA instead of `latest`?**
`latest` is a mutable pointer — it can't answer "what's in prod?", can't be rolled
back to, and isn't reproducible. A SHA tag is immutable, so a deploy is pinned and
rollback is just deploying the old tag.

**Q: How do you know a deploy actually worked?**
`docker compose up` succeeding only proves the **container started**. The app can
start and immediately fail on bad config. So the pipeline polls a real health
endpoint and only calls it green when the service **responds**. Started ≠ healthy.

**Q: What's your rollback strategy?**
Capture the running image tag *before* deploying. Deploy the new SHA, health-check,
and on failure redeploy the captured tag — then still fail the workflow so a human
is notified. Rollback is cheap precisely *because* artifacts are immutable.

**Q: Do you have zero-downtime deploys?**
**Honestly, no** — and I'd say so. `docker compose up -d` **recreates** the container,
so there's a few seconds of downtime. On a single 1 GB box that's a deliberate
trade-off: true zero-downtime needs two instances behind a load balancer
(**blue-green** — run both, flip the LB, keep the old one warm for instant rollback)
or a **rolling** update across replicas. That costs at least double the infrastructure.
For a code-execution agent where submissions are queued and retried, a few seconds
of unavailability is acceptable; if it weren't, blue-green behind an ALB is the fix.

**Q: How do you handle secrets?**
Nothing sensitive is in the repo. The SSH key and host are GitHub Actions secrets
(encrypted at rest, masked in logs). The registry uses the auto-injected, job-scoped
`GITHUB_TOKEN`. The runtime token lives in an untracked `.env` on the box. The next
step up is **OIDC** — federate into an IAM role for a short-lived token so there are
no long-lived cloud credentials anywhere.

**Q: What is "push" vs "pull" deployment?**
Ours is **push**: CI reaches *into* the server and tells it what to do (needs inbound
access + credentials). The **pull** model (GitOps — ArgoCD/Flux) inverts it: an agent
*inside* the cluster watches the repo and pulls changes itself. Pull needs **no
inbound ports and no cluster credentials in CI**, and the repo is the single source of
truth. It's the standard for Kubernetes; overkill for one EC2 box.

**Q: Why the `paths:` filter and the concurrency group?**
`paths:` stops a README edit from restarting production. `concurrency` stops two
pushes from deploying simultaneously and corrupting state — and I set
`cancel-in-progress: false` because cancelling a deploy *mid-flight* is worse than
letting it finish. Cancellation is safe for pure-compute jobs, not for jobs with
side effects.

**Q: How would you scale this beyond one box?**
Put the agents behind a load balancer, deploy rolling or blue-green, and move image
distribution to a registry the whole fleet pulls from (already done). At that point
the SSH-loop doesn't scale, and you'd switch to ECS/Kubernetes with a declarative
deployment (or GitOps), where you change a manifest's image tag and the orchestrator
handles draining and replacing instances.

---

## Part 8 — Known gaps / what I'd do next

1. **Zero downtime.** Currently a few seconds. Fix = two instances + a load balancer
   (blue-green).
2. **SSH is push-based over port 22.** Fix = AWS SSM Session Manager (no inbound port).
3. **No staging environment.** Everything green goes straight to prod. Fix = deploy to
   a staging EC2 first, run a smoke test, then promote **the same image** to prod.
4. **Thin test suite.** The CI gate is only as good as the tests behind it. An
   end-to-end test that submits real code and asserts the verdict would be the highest-value addition.
