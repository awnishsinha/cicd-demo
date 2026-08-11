# 🚀 CI/CD Practical Learning — Comprehensive Notes

> **A complete guide to building a working CI/CD pipeline for a Spring Boot application — from local development to automated cloud deployment.**

---

## 📋 Table of Contents

1. [What is CI/CD?](#what-is-cicd)
2. [The Full Pipeline Architecture](#the-full-pipeline-architecture)
3. [Technology Stack](#technology-stack)
4. [Maven — Building the JAR](#maven--building-the-jar)
5. [Docker — Packaging the Application](#docker--packaging-the-application)
6. [Multi-Stage Docker Builds](#multi-stage-docker-builds)
7. [GitHub Actions — Automation](#github-actions--automation)
8. [GitHub Secrets — Securing Credentials](#github-secrets--securing-credentials)
9. [AWS EC2 & SSH — Deployment Target](#aws-ec2--ssh--deployment-target)
10. [Security Group Troubleshooting](#security-group-troubleshooting)
11. [Deployment Script Deep Dive](#deployment-script-deep-dive)
12. [Security Best Practices](#security-best-practices)
13. [Important Commands Reference](#important-commands-reference)
14. [Interview Q&A](#interview-qa)
15. [Lessons Learned](#lessons-learned)
16. [Next Steps Roadmap](#next-steps-roadmap)

---

## What is CI/CD?

CI/CD stands for **Continuous Integration** and **Continuous Delivery/Deployment**. The goal is simple: automate everything that happens between a developer writing code and that code running in production.

### Continuous Integration (CI)

Every time a developer pushes code, an automated system kicks in to validate it.

```
git push
   ↓
Checkout source code
   ↓
Compile
   ↓
Run tests
   ↓
Build artifact (JAR)
```

**Why it matters:** If a developer accidentally introduces a bug like this:

```java
// Bug: referencing a variable that doesn't exist
return unknownVariable;
```

The CI pipeline catches it *before* the code ever reaches production. No manual intervention needed.

### Continuous Delivery vs. Continuous Deployment (CD)

| Term | Meaning |
|------|---------|
| **Continuous Delivery** | Application is automatically built and *prepared* for deployment — but a human approves before it goes live |
| **Continuous Deployment** | After CI passes, the deployment happens *automatically* with no human gate |

> **Our project** implements **Continuous Deployment**: pushing to `main` triggers the pipeline which automatically deploys to EC2.

---

## The Full Pipeline Architecture

Here's the complete end-to-end picture of what happens when you run `git push`:

```
Developer
    │
    │  git push origin main
    ▼
GitHub Repository
    │
    ▼
GitHub Actions (automated runner)
    │
    ├── Step 1: Checkout source code
    ├── Step 2: Setup Java 17
    ├── Step 3: chmod +x mvnw
    ├── Step 4: ./mvnw clean package (compile + test + JAR)
    ├── Step 5: docker build → Docker image
    ├── Step 6: docker push → Docker Hub
    │
    └── Step 7: SSH into EC2
              │
              ├── docker pull (get latest image)
              ├── docker stop cicd-demo (kill old container)
              ├── docker rm cicd-demo (remove old container)
              └── docker run (start new container on port 9091)
```

### The Real-World Analogy

Think of it like a car factory assembly line:
- **Git push** = Parts arrive at the factory
- **Maven** = Workers assemble the parts into a car (JAR)
- **Docker build** = Car gets wrapped in packaging for shipping (Image)
- **Docker Hub** = Shipping warehouse
- **EC2 deploy** = Car delivered to the showroom and put on display

---

## Technology Stack

Each technology has a **specific, non-overlapping responsibility**:

| Technology | Responsibility | Example |
|------------|---------------|---------|
| **Git** | Track code changes (version control) | `git commit`, `git push` |
| **GitHub** | Remote repository, collaboration | Stores your source code online |
| **GitHub Actions** | Automation engine | Runs jobs defined in `.github/workflows/` |
| **Maven** | Build, test, and package Java app | Produces `cicd-demo-0.0.1-SNAPSHOT.jar` |
| **JAR** | Packaged, runnable Java application | `java -jar app.jar` starts the app |
| **Docker** | Package app into a portable container image | `docker build`, `docker run` |
| **Docker Hub** | Store and distribute Docker images | Like "GitHub for Docker images" |
| **AWS EC2** | Cloud server where app actually runs | Ubuntu virtual machine on AWS |
| **SSH** | Secure remote terminal access to EC2 | `ssh -i key.pem ubuntu@<ip>` |
| **Security Group** | AWS firewall — controls network access | Allow port 9091 from the internet |

---

## Maven — Building the JAR

### The Project Structure (Before Maven)

When you first clone the project, there is **no `target/` directory**:

```
cicd-demo/
├── src/
│   └── main/java/...    ← Your Spring Boot code
├── pom.xml              ← Maven configuration
├── Dockerfile
└── mvnw                 ← Maven wrapper script
```

### Running Maven

```bash
./mvnw clean package
```

Maven executes four phases in order:

```
clean     → Delete old build output (removes target/)
   ↓
compile   → Translate .java files into .class bytecode
   ↓
test      → Execute all unit tests (fails build if tests fail)
   ↓
package   → Bundle everything into a JAR file
```

### The Project Structure (After Maven)

```
cicd-demo/
├── src/
├── pom.xml
├── Dockerfile
├── mvnw
└── target/                          ← Created by Maven
    ├── classes/                     ← Compiled .class files
    ├── test-classes/                ← Compiled test files
    └── cicd-demo-0.0.1-SNAPSHOT.jar ← The runnable JAR ✅
```

### `mvn test` vs `mvn package` — Know the Difference

```bash
# ONLY compiles and runs tests — does NOT create the JAR
./mvnw clean test

# Compiles, tests, AND creates the JAR
./mvnw clean package
```

> **For CI/CD:** Always use `./mvnw clean package`. The Docker build needs the JAR, so `test` alone is insufficient.

### Why `-DskipTests` Is Tempting but Wrong

```bash
# ❌ Skips tests — fast, but defeats the purpose of CI
./mvnw package -DskipTests

# ✅ Runs tests first, then packages
./mvnw clean package
```

If a step in your pipeline is named "Run test and build" but actually skips tests, that's misleading and defeats the purpose of CI.

---

## Docker — Packaging the Application

### Our Dockerfile, Line by Line

```dockerfile
FROM eclipse-temurin:17-jre       # ← Use Java 17 runtime as base image

WORKDIR /app                       # ← Set working directory inside container

COPY target/*.jar app.jar          # ← Copy JAR from host into image

EXPOSE 9091                        # ← Document that app uses port 9091

ENTRYPOINT ["java", "-jar", "app.jar"]  # ← Command to run when container starts
```

**Breaking down each instruction:**

| Instruction | What it does | Important detail |
|-------------|-------------|-----------------|
| `FROM` | Pulls a base image with Java 17 JRE | JRE (not JDK) — only needs to *run*, not compile |
| `WORKDIR` | Creates and sets `/app` as the working directory | Like `cd /app && mkdir -p /app` |
| `COPY` | Copies the Maven JAR into the image | The `*` wildcard matches any version of the JAR name |
| `EXPOSE` | Documents the port — informational only | Does **not** publish the port to the host |
| `ENTRYPOINT` | What runs when the container starts | `java -jar app.jar` launches Spring Boot |

### Why `docker build` Fails Without Maven First

If you skip Maven and try to build the Docker image directly:

```
docker build -t myapp .
    ↓
Docker reads Dockerfile
    ↓
Reaches: COPY target/*.jar app.jar
    ↓
Looks in target/ on host machine
    ↓
❌ No JAR found! Build fails.
```

The error looks like:
```
COPY failed: file not found in build context or excluded by .dockerignore: stat target/*.jar
```

### The Correct Local Build Flow

```bash
# Step 1: Create the JAR
./mvnw clean package

# Step 2: Build the Docker image
docker build -t myapp .

# Step 3: Run the container
docker run -d \
  --name cicd-demo \
  -p 9091:9091 \
  myapp
```

### Docker Port Mapping Explained

```
-p 9091:9091
   │     │
   │     └── Container port (Spring Boot listens here)
   └──────── Host/EC2 port (exposed to the outside world)
```

When a browser hits `http://ec2-ip:9091`, the request flows:

```
Browser → EC2:9091 → Docker bridge → Container:9091 → Spring Boot
```

---

## Multi-Stage Docker Builds

Our single-stage Dockerfile requires Maven to run *before* `docker build`. A **multi-stage Dockerfile** bundles Maven *inside* the Docker build itself.

### The Multi-Stage Dockerfile

```dockerfile
# ─── Stage 1: Build ───────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .          # Copy pom first (layer caching optimization)
COPY src ./src          # Then copy source

RUN mvn clean package -DskipTests   # Maven runs inside Docker!

# ─── Stage 2: Runtime ─────────────────────────────────────────────────
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar   # Grab JAR from stage 1

ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Single-Stage vs Multi-Stage Comparison

```
Single-Stage Dockerfile:
  Host: maven package → JAR → docker build → Image
  
Multi-Stage Dockerfile:
  docker build → [Maven inside] → JAR → [Runtime stage] → Image
```

### Why Multi-Stage Produces a Smaller Image

```
Stage 1 — Build image contains:
├── Maven binary
├── JDK (full Java Development Kit)
├── All downloaded dependencies
└── Source code
  ↓ (only the JAR is copied out)

Stage 2 — Runtime image contains:
├── JRE (Java Runtime only)
└── app.jar
   = Much smaller, cleaner image ✅
```

| Approach | Image Contains | Image Size |
|----------|---------------|------------|
| Single-stage (copy JAR) | JRE + JAR | ~200 MB |
| Multi-stage | JRE + JAR (build tools discarded) | ~200 MB |
| Without multi-stage (if Maven included) | JDK + Maven + JAR | ~500+ MB |

---

## GitHub Actions — Automation

GitHub Actions reads workflow files stored in `.github/workflows/`. When the trigger condition is met, GitHub spins up a fresh Linux runner and executes the defined steps.

### Our Complete Workflow

```yaml
# .github/workflows/deploy.yml

name: cicd-demo

# Trigger: only on pushes to the main branch
on:
  push:
    branches:
      - main

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest   # Fresh Ubuntu VM provided by GitHub

    steps:

      # Step 1: Download the repository code onto the runner
      - name: Checkout Code
        uses: actions/checkout@v4

      # Step 2: Install Java 17 on the runner
      - name: Set up Java
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: maven

      # Step 3: Make Maven wrapper executable (Linux permission fix)
      - name: Make Maven wrapper executable
        run: chmod +x mvnw

      # Step 4: Compile, test, and package the Spring Boot app
      - name: Test and build
        run: ./mvnw clean package

      # Step 5: Authenticate with Docker Hub
      - name: Log in to Docker Hub
        uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKER_USERNAME }}
          password: ${{ secrets.DOCKER_TOKEN }}

      # Step 6: Build the Docker image
      - name: Build Docker image
        run: |
          docker build \
            -t ${{ secrets.DOCKER_USERNAME }}/cicd-demo:3.0 \
            .

      # Step 7: Push image to Docker Hub
      - name: Push Docker image
        run: |
          docker push \
            ${{ secrets.DOCKER_USERNAME }}/cicd-demo:3.0

      # Step 8: SSH into EC2 and deploy
      - name: Deploy to EC2
        uses: appleboy/ssh-action@v1.2.2
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ${{ secrets.EC2_USERNAME }}
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            docker pull ${{ secrets.DOCKER_USERNAME }}/cicd-demo:3.0
            docker stop cicd-demo || true
            docker rm cicd-demo || true
            docker run -d \
              --name cicd-demo \
              -p 9091:9091 \
              ${{ secrets.DOCKER_USERNAME }}/cicd-demo:3.0
```

### Why `chmod +x mvnw` Is Necessary

When the Maven wrapper (`mvnw`) is committed to Git on Windows, it may lose its Linux executable permission bit. On GitHub's Ubuntu runner:

```bash
./mvnw clean package
# Error: bash: ./mvnw: Permission denied
```

Fix:
```bash
chmod +x mvnw   # Grant execute permission
./mvnw clean package   # Now works ✅
```

### The `on` Trigger — Push vs Pull Request

```yaml
# ❌ Too broad — deploys on EVERY pull request too
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

# ✅ Correct — only deploy when code is merged to main
on:
  push:
    branches: [main]
```

**Why this matters:**

```
Feature branch
      ↓
Pull Request (run CI tests only)
      ↓
Code review
      ↓
Merge to main
      ↓  ← Only here should CD deploy!
Deployment to EC2
```

---

## GitHub Secrets — Securing Credentials

Never hard-code sensitive values in your workflow YAML. Use GitHub Secrets.

### Our Required Secrets

| Secret Name | What It Stores |
|-------------|---------------|
| `DOCKER_USERNAME` | Your Docker Hub username |
| `DOCKER_TOKEN` | Docker Hub access token (not your password) |
| `EC2_HOST` | The public IP or hostname of your EC2 instance |
| `EC2_USERNAME` | The SSH username (usually `ubuntu` for Ubuntu AMIs) |
| `EC2_SSH_KEY` | The full contents of your `.pem` private key file |

### How Secrets Work

```yaml
# ❌ Never do this
password: myRealDockerPassword123

# ✅ Reference the secret — GitHub injects it at runtime
password: ${{ secrets.DOCKER_TOKEN }}
```

GitHub masks secrets in logs, displaying `***` instead of the actual value. Even if someone reads your workflow file, they cannot recover the secret.

### Setting Up Secrets

1. Go to your repository → **Settings**
2. Click **Secrets and variables** → **Actions**
3. Click **New repository secret**
4. Add each secret from the table above

---

## AWS EC2 & SSH — Deployment Target

### Manual SSH Connection

```bash
# Connect from your local machine
ssh -i "amit-key-pair-mumbai.pem" ubuntu@3.110.231.97
```

### Windows SSH Permission Issue

On Windows, if your `.pem` file has overly broad permissions, SSH refuses to use it:

```
WARNING: UNPROTECTED PRIVATE KEY FILE!
Permissions for 'amit-key-pair-mumbai.pem' are too open.
```

**Fix on Windows:** Right-click `.pem` → Properties → Security → Advanced → Remove all users except yourself.

This is a **security feature**, not a bug. SSH requires private keys to be readable only by their owner.

---

## Security Group Troubleshooting

### The SSH Timeout Mystery

**Symptom:** GitHub Actions fails with:
```
dial tcp 3.110.231.97:22: i/o timeout
```

**But:** Manual SSH from your laptop works perfectly.

### Root Cause Diagnosed

Your EC2 Security Group had SSH restricted to your personal IP:

```
Type: SSH
Protocol: TCP
Port: 22
Source: 203.0.113.45/32   ← Only YOUR laptop is allowed
```

The traffic flows:

```
Your laptop (203.0.113.45)
         │ ✅ Allowed
         ▼
      EC2 :22


GitHub Actions runner (140.82.x.x — different IP!)
         │ ❌ Blocked by Security Group
         ▼
      EC2 :22
```

### Why This Happens

- Your laptop is allowed → SSH connects successfully
- GitHub's runner has a different public IP → Security Group blocks it
- **The TCP connection itself** is blocked — before any authentication can occur

### The Difference: Connection Refused vs. Auth Failure

| Error | Meaning |
|-------|---------|
| `dial tcp ...:22: i/o timeout` | Security Group blocking the TCP connection entirely |
| `Permission denied (publickey)` | TCP connected, but SSH key authentication failed |

These are completely different problems. The first is a *network* problem; the second is a *credentials* problem.

### Port 22 vs Port 9091 — Two Separate Concerns

```
Port 22 (SSH)
  └── Used by: GitHub Actions → EC2 (for deployment commands)
  └── If blocked: Deployment fails, app not reachable

Port 9091 (Spring Boot)
  └── Used by: Browser/API clients → EC2 → Docker → Spring Boot
  └── If blocked: App not reachable from the internet
```

A timeout on port 22 has **nothing to do** with whether Spring Boot is running on 9091.

---

## Deployment Script Deep Dive

### The Full Deployment Script

```bash
# 1. Download the latest image from Docker Hub BEFORE stopping anything
docker pull $DOCKER_USERNAME/cicd-demo:3.0

# 2. Stop the running container (ignore error if it doesn't exist)
docker stop cicd-demo || true

# 3. Remove the stopped container (ignore error if it doesn't exist)
docker rm cicd-demo || true

# 4. Start a fresh container with the new image
docker run -d \
  --name cicd-demo \
  -p 9091:9091 \
  $DOCKER_USERNAME/cicd-demo:3.0
```

### Why `|| true`?

On the **very first deployment**, there is no existing container named `cicd-demo`. Without `|| true`:

```bash
docker stop cicd-demo
# Error: No such container: cicd-demo
# → Deployment fails ❌
```

With `|| true`:
```bash
docker stop cicd-demo || true
# Error: No such container: cicd-demo
# → Error ignored, deployment continues ✅
```

The `||` means "OR" in bash — if the left command fails, run the right command. `true` always succeeds.

### Why Pull *Before* Stopping?

Consider this scenario:

```
Option A — Pull after stopping:
  Stop container  → App is DOWN
  Pull image      → Fails! Docker Hub unreachable
  Start container → Can't start, no image
  Result: App is DOWN permanently ❌

Option B — Pull before stopping (our approach):
  Pull image      → Success! Image cached locally
  Stop container  → App is DOWN (brief)
  Start container → Success! Uses locally cached image
  Result: Brief downtime only ✅
```

### The Deployment Sequence Visualized

```
Old version running
       │
       ▼
docker pull (new image downloaded in background — app still up)
       │
       ▼
docker stop (app goes offline — brief downtime begins)
       │
       ▼
docker rm (container removed)
       │
       ▼
docker run (new container starts — downtime ends)
       │
       ▼
New version running ✅
```

---

## Security Best Practices

### The Principle of Least Privilege

Every component should have only the **minimum permissions it actually needs**.

```
GitHub Actions
  └── Only: read repo, push to Docker Hub, SSH to deploy
  └── NOT: full AWS admin, access to other repos

EC2 Instance
  └── Only: ports 22 (SSH) and 9091 (app) open
  └── NOT: all ports open to the internet

Application
  └── Only: database permissions it uses
  └── NOT: admin database access
```

### Why `0.0.0.0/0` for SSH Is Risky

Opening SSH to all IP addresses:

```
SSH TCP 22
Source: 0.0.0.0/0   ← Allows ALL internet IPs
```

This does **not** mean everyone can log in — authentication is still required. But it does expose port 22 to:

```
Automated port scanners
Bots probing for vulnerabilities
Brute-force password attempts
Exploit testing tools
```

**This is not acceptable for production.**

### Industry-Grade Alternatives

#### Option 1: GitHub OIDC (Modern, Recommended)

```
GitHub Actions
       │
       │ OIDC token (proves it's GitHub)
       ▼
AWS IAM (validates the identity)
       │
       ▼
Temporary AWS credentials
       │
       ▼
Deploy using AWS APIs (no SSH needed)
```

No long-lived keys. No open port 22. Credentials expire automatically.

#### Option 2: AWS Systems Manager Session Manager

```
GitHub Actions
       │
       ▼
AWS Systems Manager
       │
       ▼
Private EC2 (port 22 not exposed at all!)
```

#### Option 3: Bastion Host Architecture

```
Internet
    │
    ▼
Bastion/Jump Host (port 22 open, heavily monitored)
    │
    ▼
Private EC2 (port 22 only reachable from bastion)
```

#### Option 4: AWS-Native Deployment (ECS/ECR)

```
GitHub Actions
      ↓
Docker image
      ↓
AWS ECR (private registry instead of Docker Hub)
      ↓
AWS ECS (container orchestration — no manual EC2 management)
```

### Long-Lived Keys vs. Temporary Credentials

| Approach | Risk Level | Recommendation |
|----------|-----------|----------------|
| `.pem` in GitHub Secrets | Medium | Acceptable for learning, but key is permanent |
| GitHub OIDC + IAM | Low | Preferred for production — credentials auto-expire |
| Access keys in workflow | High | Never do this |
| Secrets in source code | Critical | Never, ever do this |

---

## Important Commands Reference

### Maven

```bash
# Make Maven wrapper executable (needed on fresh Linux environments)
chmod +x mvnw

# Clean, compile, test, and create JAR (the main build command)
./mvnw clean package

# Clean, compile, and test only (no JAR created)
./mvnw clean test

# Build JAR but skip tests (use sparingly)
./mvnw package -DskipTests
```

### Docker

```bash
# Build an image from the Dockerfile in the current directory
docker build -t myapp .

# Build a tagged image for Docker Hub
docker build -t username/cicd-demo:3.0 .

# Push image to Docker Hub
docker push username/cicd-demo:3.0

# Pull image from Docker Hub
docker pull username/cicd-demo:3.0

# Run a container in detached mode with port mapping
docker run -d --name cicd-demo -p 9091:9091 username/cicd-demo:3.0

# Stop a running container
docker stop cicd-demo

# Remove a stopped container
docker rm cicd-demo

# List running containers
docker ps

# Show container logs
docker logs cicd-demo

# Follow logs in real-time
docker logs -f cicd-demo
```

### SSH

```bash
# Connect to EC2 (Linux/Mac)
ssh -i "amit-key-pair-mumbai.pem" ubuntu@3.110.231.97
```

### EC2 Diagnostics

```bash
# Find the public IP of the EC2 instance
curl -4 ifconfig.me

# Check if Ubuntu's built-in firewall (ufw) is blocking ports
sudo ufw status

# Verify your Spring Boot app is actually listening on 9091
sudo netstat -tlnp | grep 9091
```

---

## Interview Q&A

### Q1: What is CI/CD?

**Answer:** CI/CD is a software delivery practice where code integration, testing, building, and deployment are automated. CI automatically validates every code change by compiling and testing it. CD takes the validated build and automatically makes it available in a target environment. Together they reduce manual effort, catch bugs earlier, and enable faster, more reliable releases.

---

### Q2: What happens when you run `git push` in your project?

**Answer:** A GitHub Actions workflow is triggered. It checks out the source code onto a fresh Ubuntu runner, sets up Java 17, grants execute permission to the Maven wrapper, runs `./mvnw clean package` to compile, test, and create a JAR, builds a Docker image containing that JAR, pushes the image to Docker Hub, then SSH's into the EC2 instance where it pulls the new image, stops and removes the old container, and starts a new container with the updated application.

---

### Q3: Why must Maven run before `docker build`?

**Answer:** Our Dockerfile contains `COPY target/*.jar app.jar`. This instruction tells Docker to copy an already-built JAR from the `target/` directory on the host machine into the image. If Maven hasn't run yet, `target/` doesn't contain a JAR, and Docker cannot find the file to copy — causing the build to fail. Maven must compile, test, and package the application first to create that JAR.

---

### Q4: Does Docker always need a JAR file?

**Answer:** No — it depends entirely on what the Dockerfile instructs. Our Dockerfile copies an externally-built JAR, so Maven must create it first. Alternatively, a multi-stage Dockerfile can run Maven *inside* the Docker build process itself, creating the JAR in a build stage and then copying only the JAR into the final runtime image. In that case, Maven runs during `docker build` — no pre-built JAR needed.

---

### Q5: Why did GitHub Actions SSH fail when manual SSH worked?

**Answer:** The EC2 Security Group restricted SSH (port 22) to our personal laptop's public IP address. Manual SSH from our laptop was allowed. But GitHub Actions runs on GitHub-hosted runners which have different public IP addresses — those IPs were not in our Security Group's allow list, so the TCP connection timed out before reaching the SSH server. The fix is to allow the GitHub runner's IP range in the Security Group.

---

### Q6: What's the difference between `i/o timeout` and `Permission denied (publickey)`?

**Answer:** An `i/o timeout` on port 22 means the TCP connection never reached the SSH server — it was blocked by a firewall or Security Group. The problem is at the *network* layer. `Permission denied (publickey)` means the TCP connection succeeded and reached the SSH server, but the authentication failed — the wrong key was presented. These are completely different problems requiring different solutions.

---

### Q7: Why do you use `docker stop cicd-demo || true`?

**Answer:** On the very first deployment, no container named `cicd-demo` exists yet. Running `docker stop cicd-demo` would fail with "No such container," and that failure would abort the entire deployment. The `|| true` pattern means "if this command fails, treat it as success and continue." It makes the deployment script idempotent — safe to run whether or not a container already exists.

---

### Q8: What security improvements would you make to this pipeline?

**Answer:** Several improvements: (1) Use GitHub Actions OIDC instead of long-lived SSH keys to authenticate with AWS using temporary, auto-expiring credentials. (2) Move to AWS Systems Manager Session Manager to eliminate the need for a publicly exposed SSH port entirely. (3) Use AWS ECR instead of Docker Hub as a private image registry integrated with IAM. (4) Apply the principle of least privilege — give the GitHub Actions role only the specific permissions it needs, nothing more. (5) Use Git commit SHAs as Docker image tags for full traceability and reliable rollback.

---

### Q9: What does `EXPOSE 9091` do in a Dockerfile?

**Answer:** `EXPOSE` is documentation — it tells developers and tooling that the container listens on port 9091. It does **not** actually publish or expose the port to the host machine. Publishing happens at runtime with the `-p 9091:9091` flag in `docker run`, which maps EC2 port 9091 to container port 9091.

---

### Q10: What is `0.0.0.0/0` in a Security Group context?

**Answer:** `0.0.0.0/0` represents all IPv4 addresses on the internet. Setting an SSH rule with source `0.0.0.0/0` means any internet host can attempt a TCP connection to port 22. Authentication is still required to actually log in, but it dramatically increases the attack surface by exposing the SSH service to automated scanners, bots, and brute-force tools. It's acceptable for a quick test but not for production environments.

---

## Lessons Learned

### 1. CI/CD Is a Chain — Understand Each Link

```
Git → GitHub → GitHub Actions → Maven → JAR → Docker → Registry → EC2 → Container → App
```

When something breaks, identify *which link* in the chain failed. An SSH timeout has nothing to do with your Spring Boot code. A Maven failure has nothing to do with Docker Hub.

### 2. Docker Doesn't Replace Maven (They Do Different Things)

```
Maven    →  Java source code → JAR        (builds the Java application)
Docker   →  JAR → Container image         (packages it for deployment)
```

They're complementary — not alternatives to each other.

### 3. Your Laptop and GitHub's Runner Are Different Machines

```
Your laptop → Allowed in EC2 Security Group  ✅
GitHub runner → Different IP → Not allowed  ❌
```

Always ask: "Does this permission apply to the machine *actually* running the command?"

### 4. Network Problems and Auth Problems Have Different Error Messages

| Error | Root Cause | Fix |
|-------|-----------|-----|
| `i/o timeout` | TCP blocked (Security Group) | Update firewall rules |
| `Permission denied (publickey)` | Wrong SSH key | Use correct `.pem` file |
| `No such container` | Container doesn't exist | Use `|| true` in script |
| `COPY target/*.jar failed` | JAR not built yet | Run `./mvnw package` first |

### 5. Security Can't Be an Afterthought

Every shortcut taken in security (hardcoded passwords, open ports, overly broad IAM permissions) is technical debt that accumulates risk. Build security in from the start.

---

## Next Steps Roadmap

### Step 1 — Traceability: Use Git SHA as Docker Tag

```bash
# Instead of a manual version number:
cicd-demo:3.0

# Use the Git commit hash:
cicd-demo:a81f92c
```

In GitHub Actions:
```yaml
- name: Build Docker image
  run: |
    docker build \
      -t ${{ secrets.DOCKER_USERNAME }}/cicd-demo:${{ github.sha }} \
      .
```

Now every image is traceable to an exact commit.

### Step 2 — Rollback Strategy

With immutable image tags (Git SHAs), rolling back becomes:

```bash
# Roll back to a specific commit's image
docker pull username/cicd-demo:a81f92c
docker stop cicd-demo && docker rm cicd-demo
docker run -d --name cicd-demo -p 9091:9091 username/cicd-demo:a81f92c
```

### Step 3 — Health Checks After Deployment

```yaml
# After docker run, verify the app actually started
- name: Health Check
  run: |
    sleep 10
    curl --fail http://${{ secrets.EC2_HOST }}:9091/actuator/health
```

### Step 4 — Separate CI and CD Workflows

```
.github/workflows/
├── ci.yml       ← Runs on ALL branches: compile, test, build
└── deploy.yml   ← Runs on main ONLY: push image, deploy to EC2
```

### Step 5 — Move to AWS ECR

```
GitHub Actions → Docker Hub → EC2   (current)

GitHub Actions → AWS ECR → EC2      (future — private, IAM-integrated)
```

### Step 6 — GitHub Actions OIDC (No Long-Lived Keys)

```yaml
permissions:
  id-token: write   # Required for OIDC

- name: Configure AWS credentials
  uses: aws-actions/configure-aws-credentials@v4
  with:
    role-to-assume: arn:aws:iam::123456789:role/GitHubActionsRole
    aws-region: ap-south-1
```

No `.pem` file. No stored keys. Credentials auto-expire after the job completes.

---

## Final Mental Model

The most important thing to internalize: **every stage has one job, and they form an unbroken chain**.

```
DEVELOPER WRITES CODE
       │
       │  git push
       ▼
GITHUB (stores code, triggers automation)
       │
       ▼
GITHUB ACTIONS (the automation engine)
       │
       ├── MAVEN (compiles Java, runs tests, creates JAR)
       │         └─→ JAR file
       │
       ├── DOCKER BUILD (packages JAR into a container image)
       │         └─→ Docker Image
       │
       ├── DOCKER HUB (stores and distributes images)
       │         └─→ Image registry
       │
       └── SSH → EC2 (runs the new container)
                 └─→ Spring Boot listening on :9091
                 └─→ Users can access the application
```

And the key transformation pipeline:

```
Java Source
    │  (Maven)
    ▼
    JAR
    │  (Docker build)
    ▼
Docker Image
    │  (Docker push)
    ▼
Docker Hub
    │  (docker pull on EC2)
    ▼
Container
    │
    ▼
Running Application 🎉
```

---

*This document covers a complete, practical CI/CD implementation — from understanding the concepts to deploying a Spring Boot application automatically on AWS EC2 using GitHub Actions, Docker, and SSH.*
