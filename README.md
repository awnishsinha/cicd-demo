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


# 🚀 CI/CD Advanced Learning — Part 2
## From Docker SHA Tagging to Dynamic Blue-Green Deployment

> **Building on the fundamentals — this document covers the advanced CI/CD concepts learned through practical implementation: image versioning, health checks, rollback, reverse proxies, and dynamic Blue-Green deployment.**

---

## 📋 Table of Contents

1. [Docker Image Versioning with Git SHA](#docker-image-versioning-with-git-sha)
2. [Rollback — The Fundamentals](#rollback--the-fundamentals)
3. [Deployment Health Checks](#deployment-health-checks)
4. [Spring Boot Actuator](#spring-boot-actuator)
5. [Health Check Integration in GitHub Actions](#health-check-integration-in-github-actions)
6. [Reverse Proxy — What It Is and Why](#reverse-proxy--what-it-is-and-why)
7. [Nginx Installation and Configuration](#nginx-installation-and-configuration)
8. [Blue-Green Deployment — Manual Practice](#blue-green-deployment--manual-practice)
9. [Automatic Rollback](#automatic-rollback)
10. [Dynamic Blue-Green Deployment](#dynamic-blue-green-deployment)
11. [The Complete Final Workflow](#the-complete-final-workflow)
12. [Common Bugs and Pitfalls](#common-bugs-and-pitfalls)
13. [Interview Q&A — Advanced Topics](#interview-qa--advanced-topics)
14. [Architecture Evolution Summary](#architecture-evolution-summary)

---

## Docker Image Versioning with Git SHA

### The Problem with `:3.0`

Our original pipeline always tagged the Docker image with the same version:

```yaml
docker build -t username/cicd-demo:3.0 .
```

Suppose you make three commits:

```
Commit A → "Fix login bug"
Commit B → "Add new dashboard"
Commit C → "Update homepage"
```

Every push produces:

```
cicd-demo:3.0
```

...and overwrites the previous one. After Commit C, Docker Hub shows:

```
cicd-demo:3.0 → Version C only (A and B are gone)
```

**Problems this creates:**
- You cannot tell which Git commit produced the running image
- Rolling back requires knowing a specific version number you may not have recorded
- "What was deployed 3 days ago?" becomes unanswerable

### The Solution: Git SHA as Docker Tag

GitHub Actions automatically provides the commit SHA through `github.sha`. For example:

```
Commit: a81f92c8e7b1...
```

Change the build step to:

```yaml
- name: Build Docker image
  run: |
    docker build \
      -t ${{ secrets.DOCKER_USERNAME }}/cicd-demo:${{ github.sha }} \
      .

- name: Push Docker image
  run: |
    docker push \
      ${{ secrets.DOCKER_USERNAME }}/cicd-demo:${{ github.sha }}
```

Now Docker Hub contains:

```
cicd-demo:a81f92c   ← Commit A
cicd-demo:b72e91d   ← Commit B
cicd-demo:c99f12a   ← Commit C
```

Every image is **traceable to an exact Git commit**.

### Update EC2 Deployment to Use SHA

The EC2 deployment script must also use the SHA:

```yaml
- name: Deploy to EC2
  uses: appleboy/ssh-action@v1.2.2
  with:
    host: ${{ secrets.EC2_HOST }}
    username: ${{ secrets.EC2_USERNAME }}
    key: ${{ secrets.EC2_SSH_KEY }}
    script: |
      docker pull ${{ secrets.DOCKER_USERNAME }}/cicd-demo:${{ github.sha }}

      docker stop cicd-demo || true
      docker rm cicd-demo || true

      docker run -d \
        --name cicd-demo \
        -p 9091:9091 \
        ${{ secrets.DOCKER_USERNAME }}/cicd-demo:${{ github.sha }}
```

### Three Tagging Strategies Compared

| Strategy | Example | Use Case |
|----------|---------|----------|
| Version number | `cicd-demo:3.0` | Human-friendly, easy to reference |
| Git SHA | `cicd-demo:a81f92c` | Precise traceability, reliable rollback |
| Both | `cicd-demo:3.0` AND `cicd-demo:a81f92c` | Best of both worlds (production ideal) |

> **Why not `latest`?** The `latest` tag is a moving target — it always points to whatever was pushed most recently. In production, you can't answer "what version is running?" if everything is tagged `:latest`.

### The Full Traceability Chain

```
Developer writes code
        ↓
git commit (creates SHA: a81f92c)
        ↓
git push triggers GitHub Actions
        ↓
Docker image built with tag: cicd-demo:a81f92c
        ↓
Image pushed to Docker Hub
        ↓
EC2 pulls cicd-demo:a81f92c
        ↓
Container runs cicd-demo:a81f92c
        ↓
"What's running?" → a81f92c → exact Git commit
```

---

## Rollback — The Fundamentals

### Deployment Rollback vs. Git Revert

These are two completely different things:

| Concept | What It Does | When to Use |
|---------|-------------|-------------|
| **Deployment Rollback** | Changes *which application version is running* on the server | Production has a bug, restore the previous working version immediately |
| **Git Revert** | Creates a new commit that reverses a previous commit in source history | You want to permanently undo a change through the normal code review process |

Rollback does **not** touch your Git history. It only changes what's deployed.

### Why SHA Tags Make Rollback Reliable

With static tags (`:3.0`), rollback is ambiguous:

```
# What does "rollback to 3.0" even mean if 3.0 was overwritten?
docker run cicd-demo:3.0   # Which version is this?
```

With SHA tags, rollback is precise:

```
Currently running:  cicd-demo:def456   ← broken
Previous version:   cicd-demo:abc123   ← known good

Rollback:
docker pull username/cicd-demo:abc123
docker stop cicd-demo
docker rm cicd-demo
docker run -d --name cicd-demo -p 9091:9091 username/cicd-demo:abc123
```

### Finding the Previous Image on EC2

```bash
# See what's currently running and its image
docker ps

# See all images available locally
docker images

# Inspect the exact image a container is using
docker inspect --format='{{.Config.Image}}' cicd-demo
```

### The Immutable Artifact Principle

A core production CI/CD principle:

> **Once an image is created and tagged with a unique version, that image should always represent exactly that commit — never overwritten.**

```
cicd-demo:abc123  →  always  →  Git commit abc123
cicd-demo:def456  →  always  →  Git commit def456
```

This gives us **traceability**, **reproducibility**, and **reliable rollback**.

---

## Deployment Health Checks

### The Critical Gap in Basic Deployments

Most beginner pipelines stop here:

```bash
docker run -d --name cicd-demo -p 9091:9091 IMAGE
# Pipeline says ✅ "Deployment successful"
```

But `docker run` succeeding only means Docker created the container. It does **not** mean:

```
Spring Boot started         ✅ ?
Application is healthy      ✅ ?
HTTP endpoints work         ✅ ?
Database connection works   ✅ ?
```

### What Can Go Wrong After `docker run`

```
Container starts
      ↓
JVM starts
      ↓
Spring Boot initializes
      ↓
Application tries to connect to database
      ↓
❌ Connection refused
      ↓
Application crashes
      ↓
Container stops (Exited)
```

To a naive pipeline, the deployment "succeeded." To users, the site is down.

### `docker ps` vs `docker ps -a`

```bash
# Shows only RUNNING containers
docker ps

# Shows ALL containers (running + stopped/crashed)
docker ps -a
```

If your application crashes after starting, `docker ps` shows nothing, but `docker ps -a` shows:

```
NAME         STATUS
cicd-demo    Exited (1) 30 seconds ago
```

### The Retry Health Check Pattern

Instead of blindly trusting `docker run`, add a retry loop that waits for the application to genuinely respond:

```bash
docker run -d \
  --name cicd-demo \
  -p 9091:9091 \
  IMAGE

echo "Waiting for application to start..."

for i in {1..30}; do
  if curl --fail http://localhost:9091/actuator/health; then
    echo "Application is healthy!"
    exit 0
  fi

  echo "Waiting... Attempt $i/30"
  sleep 2
done

echo "Application failed to become healthy"
exit 1
```

**How the timing works:**

```
Attempt 1 → curl → fails → sleep 2s
Attempt 2 → curl → fails → sleep 2s
...
Attempt 15 → curl → ✅ SUCCESS → exit 0
```

The loop gives Spring Boot up to **60 seconds** (30 × 2s) to start. If it responds before then, we stop early. If it never responds, the deployment fails.

### Deployment Success vs. Application Success

| Check | What It Validates | Good Enough? |
|-------|------------------|-------------|
| `docker run` exits 0 | Container was created | No |
| `docker ps` shows "Up" | Container process is running | No |
| `/actuator/health` returns 200 | Application is actually responding | Yes |
| Business API returns expected response | Application logic works | Best |

---

## Spring Boot Actuator

### What Is It?

Spring Boot Actuator is a sub-project that adds production-ready monitoring endpoints to your application automatically. The most important one for CI/CD:

```
GET /actuator/health
→ {"status":"UP"}
```

### Adding Actuator to Your Project

In `pom.xml`, inside `<dependencies>`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

No version needed — Spring Boot's parent POM manages the version automatically.

### Testing the Health Endpoint

After adding the dependency and restarting:

```bash
# From EC2 or local machine
curl http://localhost:9091/actuator/health
```

Response:

```json
{"status":"UP"}
```

### Why Use Actuator Instead of a Business Endpoint?

```
Business endpoint: /api/hello
→ Returns application data
→ Depends on business logic
→ May require authentication

Actuator: /actuator/health
→ Returns health status only
→ Designed specifically for health monitoring
→ Typically public (no auth required)
→ Used by CI/CD, load balancers, and Kubernetes
```

The CI/CD pipeline should depend on infrastructure-level health, not business logic. If you check `/api/hello`, a change in your API contract could break your deployment pipeline even if the app is perfectly healthy.

### Two-Level Verification

A mature deployment verifies both:

```
Level 1 — Infrastructure health:
  /actuator/health → {"status":"UP"}
  Confirms: JVM running, Spring Boot started, components OK

Level 2 — Application health:
  /api/hello → "Hello World"
  Confirms: Business logic works, routes registered
```

---

## Health Check Integration in GitHub Actions

### Putting It All Together

Replace the basic EC2 deployment script with one that includes the retry health check:

```yaml
- name: Deploy to EC2
  uses: appleboy/ssh-action@v1.2.2
  with:
    host: ${{ secrets.EC2_HOST }}
    username: ${{ secrets.EC2_USERNAME }}
    key: ${{ secrets.EC2_SSH_KEY }}
    script: |
      IMAGE=${{ secrets.DOCKER_USERNAME }}/cicd-demo:${{ github.sha }}

      docker pull $IMAGE

      docker stop cicd-demo || true
      docker rm cicd-demo || true

      docker run -d \
        --name cicd-demo \
        -p 9091:9091 \
        $IMAGE

      echo "Waiting for application to become healthy..."

      for i in {1..30}; do
        if curl --fail http://localhost:9091/actuator/health; then
          echo "Application is healthy!"
          exit 0
        fi

        echo "Waiting... Attempt $i/30"
        sleep 2
      done

      echo "Application failed to become healthy after 60 seconds"
      exit 1
```

### What Changes in GitHub Actions

When the health check fails:

```
docker run succeeds
      ↓
Loop starts
      ↓
Attempt 1 → curl → ❌
Attempt 2 → curl → ❌
...
Attempt 30 → curl → ❌
      ↓
exit 1
      ↓
GitHub Actions step: FAILED ❌
```

GitHub reports the deployment as failed, not successful. This is the correct behavior — it prevents false positives where your pipeline says "deployed!" while the application is actually down.

---

## Reverse Proxy — What It Is and Why

### The Core Concept

A **proxy** is a middleman. The word "reverse" describes *whose behalf* the proxy is acting on.

#### Forward Proxy — acts on behalf of the CLIENT

```
Employee
    ↓
Company Proxy
    ↓
Internet

The proxy represents the CLIENT.
External servers see the proxy, not the employee.
```

#### Reverse Proxy — acts on behalf of the SERVER

```
Internet Users
      ↓
Nginx (Reverse Proxy)
      ↓
Backend Servers

The proxy represents the SERVERS.
Users see Nginx, not the internal servers.
```

The easiest way to remember it:

```
Forward Proxy:  Client → Proxy → Internet
                         ↑
                   represents CLIENT

Reverse Proxy:  Client → Proxy → Server
                         ↑
                   represents SERVER
```

### The Real-World Analogy

Imagine a large company with many departments:

```
Customer
    ↓
Reception Desk   ← The reverse proxy
    ↓
Appropriate Department (HR, Finance, Engineering)
```

The customer doesn't walk directly to employee #382. They talk to reception, which routes them appropriately. Reception:
- Receives the request
- Decides where it should go
- Forwards it
- Returns the response to the customer

That's exactly what Nginx does.

### Why We Need It for Blue-Green Deployment

Without Nginx, users access our app directly:

```
User → http://EC2-IP:9091 → Blue v1
```

With Nginx:

```
User → http://EC2-IP → Nginx → Blue v1 (or Green v2)
```

The user's URL never changes. We can silently swap Blue for Green behind Nginx — the user never knows which version they're hitting.

### What Nginx Can Do

| Capability | Description |
|------------|-------------|
| **Reverse proxy** | Forward requests to a backend server |
| **Load balancing** | Distribute requests across multiple servers |
| **SSL termination** | Handle HTTPS, forward HTTP to backends |
| **Traffic routing** | Send `/api/*` to one service, `/admin/*` to another |
| **Blue-Green switching** | Change which backend receives production traffic |

For our project, we primarily use Nginx as a **reverse proxy** for traffic switching, not load balancing.

---

## Nginx Installation and Configuration

### Install Nginx on Ubuntu (EC2)

```bash
sudo apt update
sudo apt install nginx -y

# Verify installation
nginx -v

# Check status
sudo systemctl status nginx

# Enable auto-start on reboot
sudo systemctl enable nginx
```

### Configure Nginx as a Reverse Proxy

Edit the default site configuration:

```bash
sudo nano /etc/nginx/sites-available/default
```

Replace the existing `server { }` block with:

```nginx
server {
    listen 80;
    listen [::]:80;

    location / {
        proxy_pass http://127.0.0.1:9091;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### Understanding Each Directive

| Directive | Purpose |
|-----------|---------|
| `listen 80` | Nginx listens on port 80 (HTTP) |
| `proxy_pass http://127.0.0.1:9091` | Forward all requests to Blue on port 9091 |
| `proxy_set_header Host` | Tell backend the original hostname |
| `proxy_set_header X-Real-IP` | Tell backend the real client IP |
| `X-Forwarded-For` | Chain of proxy IPs the request passed through |
| `X-Forwarded-Proto` | Original protocol (http or https) |

### Why `127.0.0.1`?

`127.0.0.1` means "this same machine" (loopback). Nginx and Docker are both running on the same EC2 instance, so Nginx forwards to the local Docker port mapping:

```
Nginx (EC2)
    ↓
127.0.0.1:9091
    ↓
Docker port 9091
    ↓
Container port 9091
    ↓
Spring Boot
```

### Test and Reload (Always in This Order)

```bash
# 1. Test the configuration BEFORE reloading — always
sudo nginx -t

# 2. Only reload if the test passes
sudo systemctl reload nginx
```

**Never skip `nginx -t`.** Reloading a broken configuration will leave Nginx in a bad state.

**`reload` vs `restart`:**
- `reload`: Applies new config while continuing to handle existing connections — minimal disruption
- `restart`: Completely stops and restarts Nginx — brief interruption to all connections

### AWS Security Group for Nginx

Update your EC2 Security Group to expose port 80:

| Port | Protocol | Source | Purpose |
|------|----------|--------|---------|
| 22 | TCP | Admin IPs | SSH access |
| 80 | TCP | 0.0.0.0/0 | HTTP (Nginx) |
| 443 | TCP | 0.0.0.0/0 | HTTPS (future) |

Ideally, **remove public access to 9091 and 9092** — those should only be accessible internally through Nginx, not directly from the internet.

### Before and After Nginx

```
Before Nginx:
User → http://EC2-IP:9091 → Spring Boot

After Nginx:
User → http://EC2-IP → Nginx → Spring Boot :9091
```

The user now accesses your app without specifying a port at all.

---

## Blue-Green Deployment — Manual Practice

### The Core Concept

Instead of:

```
Stop old version
      ↓
Start new version  ← Window where users get errors
      ↓
Hope it works
```

We do:

```
Old version keeps running (Blue)
      ↓
Start new version separately (Green)
      ↓
Test Green thoroughly
      ↓
Switch Nginx: Blue → Green (instant, no downtime)
      ↓
If Green fails → switch back to Blue immediately
```

### Docker Port Mapping for Two Environments

Your Spring Boot application listens on port `9091` inside every container. The key insight is that **different host ports can map to the same container port**:

```bash
# Blue container: EC2 port 9091 → container port 9091
docker run -d --name cicd-demo -p 9091:9091 IMAGE

# Green container: EC2 port 9092 → container port 9091
docker run -d --name cicd-demo-green -p 9092:9091 IMAGE
```

Result:

```
EC2
├── :9091 → cicd-demo (Blue) → Spring Boot :9091
└── :9092 → cicd-demo-green (Green) → Spring Boot :9091
```

Both applications internally use port 9091. Docker gives them different "doors" on the EC2 host.

**You do NOT change `server.port` in `application.properties`.** The application always runs on 9091 inside every container. Only the external host mapping changes.

### Step-by-Step Manual Blue-Green Deployment

**Step 1 — Verify Blue is running**

```bash
docker ps
curl http://localhost/api/hello          # Through Nginx
curl http://localhost/actuator/health    # Through Nginx
```

**Step 2 — Start Green alongside Blue**

```bash
# Pull the new image (new Git SHA)
docker pull username/cicd-demo:NEW_SHA

# Start Green on port 9092 — Blue is NOT stopped
docker run -d \
  --name cicd-demo-green \
  -p 9092:9091 \
  username/cicd-demo:NEW_SHA
```

**Step 3 — Test Green directly (before exposing to users)**

```bash
# Health check directly on Green (not through Nginx)
curl http://localhost:9092/actuator/health

# API smoke test directly on Green
curl http://localhost:9092/api/hello
```

At this point, users are still on Blue through Nginx. Green is being tested privately.

**Step 4 — Switch Nginx to Green**

```bash
sudo nano /etc/nginx/sites-available/default
# Change: proxy_pass http://127.0.0.1:9091;
# To:     proxy_pass http://127.0.0.1:9092;

sudo nginx -t        # Always test first
sudo systemctl reload nginx
```

**Step 5 — Verify through the full production path**

```bash
# Now testing: User → Nginx → Green → Spring Boot
curl http://localhost/api/hello
curl http://localhost/actuator/health
```

**Step 6 — Keep Blue available (don't delete yet)**

After switching, Blue is still running. This is intentional — if Green develops a problem in the next few minutes, you can instantly roll back by changing `proxy_pass` back to port 9091.

```
Current state:
                    Nginx
                      │
                      ▼ (actively serving)
                 Green :9092

                 Blue :9091
                   (standby, ready for instant rollback)
```

### Why Blue-Green Beats Stop-and-Replace

| Approach | Downtime Risk | Rollback Speed | Safety |
|----------|--------------|---------------|--------|
| Stop old → Start new | Downtime if new fails | Must rebuild/redeploy | Low |
| Blue-Green | Near zero | Instant Nginx switch | High |

---

## Automatic Rollback

### The Two-Stage Verification Model

Don't switch Nginx until Green passes all pre-switch checks. Then verify again after switching:

```
Green :9092
      │
      ├── Stage 1: Pre-switch
      │     ├── /actuator/health → ✅
      │     └── /api/hello → ✅
      │
      ↓ (only if Stage 1 passes)
      │
Switch Nginx → Green
      │
      └── Stage 2: Post-switch
            └── http://localhost/api/hello → ✅ or ❌ ROLLBACK
```

**Why two stages?**
- Stage 1 tests Green directly (no users affected if it fails)
- Stage 2 confirms the full production path through Nginx works
- If Stage 2 fails, users experienced a brief problem, but rollback is immediate

### What Rollback Looks Like in the Script

```bash
# After switching Nginx to Green...

if curl --fail http://localhost/api/hello; then

    echo "Production verification successful! Green is live."

else

    echo "Production verification FAILED! Rolling back to Blue..."

    # 1. Switch Nginx back to Blue
    sudo sed -i \
      's/proxy_pass http:\/\/127.0.0.1:9092;/proxy_pass http:\/\/127.0.0.1:9091;/' \
      /etc/nginx/sites-available/default

    # 2. Test the rollback config
    sudo nginx -t

    # 3. Reload Nginx
    sudo systemctl reload nginx

    # 4. Verify Blue is working
    if curl --fail http://localhost/api/hello; then
        echo "Rollback successful. Blue is serving traffic."
    else
        echo "CRITICAL: Rollback verification also failed!"
        exit 1
    fi

    # 5. Remove the failed Green container
    docker stop cicd-demo-green || true
    docker rm cicd-demo-green || true

    # 6. Fail the GitHub Actions job
    exit 1
fi
```

### Critical Order: Always Remove Green AFTER Switching Back

```
❌ WRONG order:
Remove Green → Switch Nginx back to Blue

  (Brief window where Nginx points to a non-existent container)


✅ CORRECT order:
Switch Nginx → Blue → Reload Nginx → Blue serving users → Remove Green

  (Users always have a working backend)
```

### Why `exit 1` After Successful Rollback?

After rollback succeeds, Blue is running fine. But the GitHub Actions job should still be marked as **failed**:

```
GitHub Actions result: ❌ FAILED
Message: "Deployment failed. Rollback to Blue successful."
```

This tells the team: "The release didn't go out. The previous version is still running." Reporting success when the intended deployment failed would be misleading.

---

## Dynamic Blue-Green Deployment

### The Problem with Hard-Coded Environments

The first version of Blue-Green always deploys to Green:

```
Deployment 1: Blue → Production, Green → New
Deployment 2: Green → Production, Green → New (WRONG — deploying to active production!)
```

After the first deployment, Nginx points to Green. The second deployment also targets Green, which is now the production environment. You'd be replacing the production container while it's serving traffic.

### The Solution: Ask Nginx What's Active

Since Nginx configuration is the source of truth for which environment is active, we read it at deployment time:

```bash
# Grep the active port from Nginx config
CURRENT_PORT=$(grep -oP 'proxy_pass http://127\.0\.0\.1:\K[0-9]+' \
  /etc/nginx/sites-available/default)

# Determine which environment is active and which is the target
if [ "$CURRENT_PORT" = "9091" ]; then

    ACTIVE_ENV="blue"
    ACTIVE_PORT=9091
    TARGET_ENV="green"
    TARGET_PORT=9092

elif [ "$CURRENT_PORT" = "9092" ]; then

    ACTIVE_ENV="green"
    ACTIVE_PORT=9092
    TARGET_ENV="blue"
    TARGET_PORT=9091

else
    echo "ERROR: Unexpected Nginx port: $CURRENT_PORT"
    exit 1
fi

CONTAINER_NAME="cicd-demo-$TARGET_ENV"
```

### The Alternating Deployment Pattern

```
Initially:
  Nginx → 9091 → Blue is production
  Target = Green

Deployment 1:
  Nginx → 9092 → Green is production
  Target = Blue

Deployment 2:
  Nginx → 9091 → Blue is production
  Target = Green

Deployment 3:
  Nginx → 9092 → Green is production
  Target = Blue
```

Blue and Green alternate roles automatically with every deployment.

### Critical Bug: Single Quotes in `sed`

The most common mistake when writing the dynamic switch:

```bash
# ❌ WRONG — single quotes prevent variable expansion
sudo sed -i \
  's/proxy_pass http:\/\/127.0.0.1:$ACTIVE_PORT;/proxy_pass http:\/\/127.0.0.1:$TARGET_PORT;/' \
  /etc/nginx/sites-available/default

# ✅ CORRECT — double quotes allow variable expansion
sudo sed -i \
  "s/proxy_pass http:\/\/127\.0\.0\.1:$ACTIVE_PORT;/proxy_pass http:\/\/127.0.0.1:$TARGET_PORT;/" \
  /etc/nginx/sites-available/default
```

With single quotes, Bash does not substitute `$ACTIVE_PORT` and `$TARGET_PORT`. The `sed` command searches for a literal string containing `$ACTIVE_PORT`, which doesn't exist in the file, so nothing changes — and you have no idea why the switch didn't work.

---

## The Complete Final Workflow

### Full GitHub Actions YAML

```yaml
name: cicd-demo

on:
  push:
    branches:
      - main

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout Code
        uses: actions/checkout@v4

      - name: Set up Java
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: maven

      - name: Make Maven wrapper executable
        run: chmod +x mvnw

      - name: Test and build
        run: ./mvnw clean package

      - name: Log in to Docker Hub
        uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKER_USERNAME }}
          password: ${{ secrets.DOCKER_TOKEN }}

      - name: Build Docker image
        run: |
          docker build \
            -t ${{ secrets.DOCKER_USERNAME }}/cicd-demo:${{ github.sha }} \
            .

      - name: Push Docker image
        run: |
          docker push \
            ${{ secrets.DOCKER_USERNAME }}/cicd-demo:${{ github.sha }}

      - name: Deploy to EC2 (Dynamic Blue-Green)
        uses: appleboy/ssh-action@v1.2.2
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ${{ secrets.EC2_USERNAME }}
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            IMAGE=${{ secrets.DOCKER_USERNAME }}/cicd-demo:${{ github.sha }}

            echo "================================"
            echo "Deploying image: $IMAGE"
            echo "================================"

            docker pull $IMAGE

            echo "================================"
            echo "Determining active environment"
            echo "================================"

            CURRENT_PORT=$(grep -oP 'proxy_pass http://127\.0\.0\.1:\K[0-9]+' \
              /etc/nginx/sites-available/default)

            if [ "$CURRENT_PORT" = "9091" ]; then
              ACTIVE_ENV="blue"
              ACTIVE_PORT=9091
              TARGET_ENV="green"
              TARGET_PORT=9092
            elif [ "$CURRENT_PORT" = "9092" ]; then
              ACTIVE_ENV="green"
              ACTIVE_PORT=9092
              TARGET_ENV="blue"
              TARGET_PORT=9091
            else
              echo "ERROR: Unable to determine active environment."
              echo "Unexpected Nginx port: $CURRENT_PORT"
              exit 1
            fi

            CONTAINER_NAME="cicd-demo-$TARGET_ENV"

            echo "Active: $ACTIVE_ENV (:$ACTIVE_PORT)"
            echo "Target: $TARGET_ENV (:$TARGET_PORT)"
            echo "Container: $CONTAINER_NAME"

            echo "================================"
            echo "Preparing $TARGET_ENV"
            echo "================================"

            docker stop $CONTAINER_NAME || true
            docker rm $CONTAINER_NAME || true

            docker run -d \
              --name $CONTAINER_NAME \
              -p $TARGET_PORT:9091 \
              $IMAGE

            echo "================================"
            echo "Health checking $TARGET_ENV"
            echo "================================"

            HEALTHY=false
            for i in {1..30}; do
              if curl --fail http://localhost:$TARGET_PORT/actuator/health; then
                HEALTHY=true
                echo "$TARGET_ENV is healthy!"
                break
              fi
              echo "$TARGET_ENV not ready. Attempt $i/30"
              sleep 2
            done

            if [ "$HEALTHY" != "true" ]; then
              echo "$TARGET_ENV deployment failed!"
              docker logs $CONTAINER_NAME
              docker stop $CONTAINER_NAME || true
              docker rm $CONTAINER_NAME || true
              exit 1
            fi

            echo "================================"
            echo "Smoke testing $TARGET_ENV"
            echo "================================"

            if curl --fail http://localhost:$TARGET_PORT/api/hello; then
              echo "$TARGET_ENV API smoke test passed!"
            else
              echo "$TARGET_ENV API smoke test FAILED!"
              docker logs $CONTAINER_NAME
              docker stop $CONTAINER_NAME || true
              docker rm $CONTAINER_NAME || true
              exit 1
            fi

            echo "================================"
            echo "Switching Nginx"
            echo "================================"

            sudo sed -i \
              "s/proxy_pass http:\/\/127\.0\.0\.1:$ACTIVE_PORT;/proxy_pass http:\/\/127.0.0.1:$TARGET_PORT;/" \
              /etc/nginx/sites-available/default

            if ! sudo nginx -t; then
              echo "Nginx configuration failed! Restoring..."
              sudo sed -i \
                "s/proxy_pass http:\/\/127\.0\.0\.1:$TARGET_PORT;/proxy_pass http:\/\/127.0.0.1:$ACTIVE_PORT;/" \
                /etc/nginx/sites-available/default
              exit 1
            fi

            sudo systemctl reload nginx

            echo "================================"
            echo "Verifying production traffic"
            echo "================================"

            if curl --fail http://localhost/api/hello; then
              echo "Production verification successful!"
              echo "$TARGET_ENV is now serving production traffic."
            else
              echo "Production verification FAILED! Rolling back to $ACTIVE_ENV..."

              sudo sed -i \
                "s/proxy_pass http:\/\/127\.0\.0\.1:$TARGET_PORT;/proxy_pass http:\/\/127.0.0.1:$ACTIVE_PORT;/" \
                /etc/nginx/sites-available/default

              if ! sudo nginx -t; then
                echo "CRITICAL: Rollback configuration is invalid!"
                exit 1
              fi

              sudo systemctl reload nginx

              echo "Verifying $ACTIVE_ENV after rollback..."
              if curl --fail http://localhost/api/hello; then
                echo "Rollback successful. $ACTIVE_ENV is serving traffic."
              else
                echo "CRITICAL: Rollback verification failed!"
                exit 1
              fi

              docker stop $CONTAINER_NAME || true
              docker rm $CONTAINER_NAME || true

              exit 1
            fi
```

### The CI vs CD Trigger

Note this workflow uses only:

```yaml
on:
  push:
    branches:
      - main
```

**Not** `pull_request`. This is important:

```
Feature branch
      ↓
Pull Request → CI only (build + test)
      ↓
Code review + merge
      ↓
main branch
      ↓
CD triggered (build + test + deploy)
```

You don't want every pull request deploying to production.

---

## Common Bugs and Pitfalls

### 1. Single Quotes Block Variable Expansion in `sed`

```bash
# ❌ Variables not expanded — sed finds nothing to replace
's/proxy_pass http:\/\/127.0.0.1:$ACTIVE_PORT;/.../'

# ✅ Variables are expanded before sed runs
"s/proxy_pass http:\/\/127\.0\.0\.1:$ACTIVE_PORT;/.../"
```

### 2. Stopping the Active Production Container

```bash
# ❌ DANGEROUS — this might be your production environment
docker stop cicd-demo-green || true
docker rm cicd-demo-green || true
# (done BEFORE determining which is active)

# ✅ Only stop the TARGET container after determining active env
CONTAINER_NAME="cicd-demo-$TARGET_ENV"
docker stop $CONTAINER_NAME || true
docker rm $CONTAINER_NAME || true
```

### 3. Removing Green Before Switching Nginx

```bash
# ❌ Gap where Nginx points to nothing
docker stop cicd-demo-green
sudo sed -i ... (switch to blue)
sudo systemctl reload nginx

# ✅ Switch Nginx first, then remove
sudo sed -i ... (switch to blue)
sudo systemctl reload nginx
docker stop cicd-demo-green
```

### 4. Not Validating Nginx Before Reload

```bash
# ❌ Could break production if config has a typo
sudo systemctl reload nginx

# ✅ Always test first
sudo nginx -t && sudo systemctl reload nginx
```

### 5. YAML Indentation in GitHub Actions

GitHub Actions YAML is indentation-sensitive. This is **invalid**:

```yaml
# ❌ Wrong
on:
push:
branches:
- main
jobs:
build-and-deploy:
```

This is **correct**:

```yaml
# ✅ Correct
on:
  push:
    branches:
      - main

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Code
        uses: actions/checkout@v4
```

Use `actionlint` to validate GitHub Actions YAML files:

```bash
# Install
choco install actionlint    # Windows
brew install actionlint     # Mac

# Run from repository root
actionlint
```

### 6. `docker run` Port Order

```bash
# -p HOST_PORT:CONTAINER_PORT
# ❌ Easy to get backwards
docker run -p 9091:9092 IMAGE  # EC2:9091 → Container:9092 (wrong)

# ✅
docker run -p 9092:9091 IMAGE  # EC2:9092 → Container:9091 (correct for Green)
```

### 7. Connection Timeout vs Authentication Failure

| Error | Root Cause | Fix |
|-------|-----------|-----|
| `dial tcp ...:22: i/o timeout` | Security Group blocking TCP connection | Allow the source IP in Security Group |
| `Permission denied (publickey)` | SSH connected but key was rejected | Use correct .pem file |
| `No such container` | Container doesn't exist | Use `|| true` for optional stops |
| `COPY target/*.jar failed` | JAR not built yet | Run Maven before Docker build |

---

## Interview Q&A — Advanced Topics

### Q1: Why do you use Git commit SHA instead of version numbers for Docker tags?

**Answer:** Version numbers like `:3.0` are applied manually and can be overwritten — if you push a new image with the same tag, you lose the previous one. Using the Git commit SHA (`github.sha`) as the tag means every image is uniquely and automatically identified. The tag directly corresponds to a commit, so you can always answer "what version is running?" by looking at the Docker image tag and finding that commit in Git history. It also makes rollback trivial — instead of figuring out what `:3.0` represented last Tuesday, you know exactly which SHA to redeploy.

---

### Q2: What is a reverse proxy and why do you use Nginx in your pipeline?

**Answer:** A reverse proxy is a server that sits between users and backend servers, receiving requests and forwarding them to the appropriate backend. It acts on behalf of the server infrastructure, hiding internal details from clients. I use Nginx as a reverse proxy in our Blue-Green deployment so that users always access the same URL (`http://EC2-IP`), while Nginx internally routes to either the Blue container on port 9091 or the Green container on 9092. Switching production traffic from one to the other is just a configuration change and an Nginx reload — the user never knows it happened.

---

### Q3: Explain Blue-Green deployment and why it's better than a stop-and-replace approach.

**Answer:** Blue-Green deployment maintains two identical environments: Blue (currently serving production traffic) and Green (where the new version is deployed and tested). While Green is being tested, Blue continues serving users without interruption. Once Green passes health checks and smoke tests, Nginx traffic is switched from Blue to Green — this switch takes milliseconds. If Green is unhealthy, we simply never switch Nginx, so users never see the broken version. In contrast, a stop-and-replace approach stops the old container before confirming the new one works, creating a window of downtime or broken requests. Blue-Green eliminates that window.

---

### Q4: What happens in your pipeline when a deployment fails?

**Answer:** Our pipeline has two failure gates. The first is pre-switch: we deploy Green, run health checks against port 9092 and smoke-test the API. If Green fails, we remove it and exit with a failure code — Nginx was never touched, so Blue continues serving all production traffic with zero user impact. The second gate is post-switch: after switching Nginx to Green, we verify the full production route. If this fails, we run automatic rollback — the `sed` command is reversed, Nginx is reloaded back to Blue, and we verify Blue is healthy before removing Green and failing the pipeline.

---

### Q5: Why must you determine the active environment dynamically instead of hard-coding "always deploy to Green"?

**Answer:** After the first successful deployment, Green becomes the production environment. If we always deploy to Green, the second deployment would replace the container that's actively serving users. By reading the Nginx configuration at deployment time and identifying which port is currently active, we know to target the inactive environment. This makes the deployment safe regardless of which environment is currently live — Blue and Green alternate roles with each deployment.

---

### Q6: What's the difference between `docker ps` and `docker ps -a`?

**Answer:** `docker ps` shows only currently running containers. `docker ps -a` shows all containers, including those that have stopped or crashed (status: "Exited"). This matters in CI/CD because a container can start and then immediately crash — `docker ps` shows nothing, but `docker ps -a` reveals `Exited (1) 10 seconds ago`. Without checking `docker ps -a`, you might think your deployment succeeded when the application actually crashed on startup.

---

### Q7: Why do you use `|| true` in the deployment script?

**Answer:** `||` means "OR" in bash — if the left command fails, execute the right command. `true` always succeeds. So `docker stop cicd-demo-green || true` means: "Try to stop the container. If it doesn't exist and the command fails, that's okay — continue." Without `|| true`, trying to stop a non-existent container on the first deployment would fail the entire pipeline. It makes stop/remove operations idempotent — safe to run whether or not the container exists.

---

### Q8: How do you validate Nginx configuration changes before applying them?

**Answer:** Before every `sudo systemctl reload nginx`, I run `sudo nginx -t`. This tests the configuration syntax without applying it. If the test fails, I restore the previous configuration using `sed` to reverse the change, then exit the deployment as failed. This prevents a situation where a bad Nginx config causes Nginx to fail on reload, taking down the reverse proxy and making the application unreachable. Validating before applying is a critical production habit.

---

## Architecture Evolution Summary

Here's how the pipeline evolved through each learning stage:

### Stage 1 — Basic Deployment (Where We Started)

```
git push → Maven → Docker → Push → EC2 → docker run
Problem: No health checks, fixed tag, no rollback
```

### Stage 2 — SHA Tagging

```
git push → Maven → Docker → Push (with :SHA) → EC2 → docker run :SHA
Improvement: Traceability, rollback possible
Problem: Still no health checks
```

### Stage 3 — Health Check Retry Loop

```
... → docker run → retry curl /actuator/health → exit 1 if unhealthy
Improvement: Catches crashed applications
Problem: Stops old container before confirming new one works
```

### Stage 4 — Nginx + Manual Blue-Green

```
... → Start Green :9092 → Test Green → Switch Nginx → Verify
Improvement: Zero downtime, instant rollback available
Problem: Always deploys to Green (unsafe after first deployment)
```

### Stage 5 — Automatic Rollback

```
... → Green → Pre-switch tests → Switch Nginx → Post-switch verify → ROLLBACK if fails
Improvement: Automatic recovery from bad deployments
```

### Stage 6 — Dynamic Blue-Green (Current)

```
... → Read Nginx config → Determine active env → Deploy to inactive env → Test → Switch
Improvement: Pipeline knows which environment is production, never deploys to active env
```

### What's Next

| Next Step | Description |
|-----------|-------------|
| Nginx upstream block | Replace `sed` hacking with a clean upstream configuration file |
| Separate CI/CD workflows | PR = build + test only; main merge = full deployment |
| AWS ECR | Private image registry with IAM integration instead of Docker Hub |
| GitHub OIDC | Temporary AWS credentials instead of long-lived SSH keys |
| Zero-downtime improvement | Handle in-flight requests during the Nginx reload |
| Monitoring & alerting | Connect health checks to PagerDuty/Slack alerts |

---

*This document covers the advanced practical CI/CD concepts built on top of the foundational pipeline — from Docker image versioning and health checks through to a production-grade dynamic Blue-Green deployment with automatic rollback.*

