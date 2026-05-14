# Heap OOM - Open Liberty Application

This is a conversion of the Quarkus heap-oom application to Open Liberty using IBM Semeru Runtime. The application simulates heap memory allocation patterns to trigger Out-Of-Memory (OOM) conditions for testing and chaos engineering purposes.

## Overview

The application provides three OOM policies:
- **request**: Allocates memory based on a fixed number of requests
- **time**: Allocates memory based on time duration and target RPS
- **realistic**: Simulates realistic allocation/deallocation patterns

## Technology Stack

- **Runtime**: IBM Semeru Runtime (OpenJ9 JVM) - Java 21
- **Application Server**: Open Liberty 24.0.0.3
- **Framework**: Jakarta EE 10 / MicroProfile 6.1
- **Build Tool**: Maven 3.9+

## Project Structure

```
heap-oom-liberty/
├── src/
│   └── main/
│       ├── java/
│       │   └── ai/causa/
│       │       ├── HeapOOM.java              # REST endpoint
│       │       ├── RestApplication.java      # JAX-RS application config
│       │       ├── consts/
│       │       │   └── Constants.java        # OOM policy enum
│       │       ├── svc/
│       │       │   └── AllocatorService.java # Core allocation logic
│       │       └── utils/
│       │           ├── StartupTime.java      # Uptime tracker
│       │           └── TimeBoundScheduler.java # Scheduled tasks
│       └── liberty/
│           └── config/
│               └── server.xml                # Liberty server configuration
├── manifests/
│   └── deployment.yaml                       # Kubernetes deployment
├── Dockerfile                                # Standard Dockerfile
├── Dockerfile.semeru                         # Semeru-specific Dockerfile
├── pom.xml                                   # Maven build configuration
└── README.md                                 # This file
```

## Building the Application

### Prerequisites
- Java 21 or later
- Maven 3.9 or later
- Docker (for containerization)

### Build with Maven

```bash
cd heap-oom-liberty
mvn clean package
```

This creates `target/heap-oom-liberty.war`

### Build Docker Image (Semeru Runtime)

```bash
docker build -f Dockerfile.semeru -t heap-oom-liberty:latest .
```

## Running the Application

### Local Development with Liberty Maven Plugin

```bash
mvn liberty:dev
```

The application will be available at `http://localhost:8080`

### Run with Docker

```bash
docker run -p 8080:8080 \
  -e JAVA_OPTS="-Xmx512m -XX:+HeapDumpOnOutOfMemoryError" \
  heap-oom-liberty:latest
```

### Deploy to Kubernetes

```bash
kubectl apply -f manifests/deployment.yaml
```

## API Endpoints

### Trigger Memory Allocation
```bash
GET /alloc/hit
```

Allocates memory according to the configured policy. Returns allocation details.

**Example Response:**
```json
{
  "policy": "request",
  "requestCount": 1,
  "bytesAllocatedThisRequest": 5242880,
  "retainedChunks": 5,
  "heapUsedBytes": 52428800,
  "heapTotalBytes": 134217728,
  "heapMaxBytes": 536870912,
  "bytesRemainingToMax": 484442112,
  "unitsLeft": 99,
  "uptimeMillis": 1234
}
```

### Check Status
```bash
GET /alloc/status
```

Returns current application status without allocating memory.

**Example Response:**
```json
{
  "policy": "request",
  "requestCount": 10,
  "retainedChunks": 50,
  "heapUsedBytes": 104857600,
  "heapTotalBytes": 268435456,
  "heapMaxBytes": 536870912,
  "uptimeMillis": 12345,
  "reqTotal": 100,
  "durationSeconds": 30,
  "targetRps": 10000,
  "timeVirtualApplied": 0,
  "timeDeadlineTriggered": false
}
```

## Configuration

Configuration is done via MicroProfile Config (environment variables or server.xml):

### OOM Policy Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `crash.oom-policy` | `request` | Policy: `request`, `time`, or `realistic` |

### Request-Bound Policy

| Property | Default | Description |
|----------|---------|-------------|
| `crash.req.total` | `100` | Total requests before OOM |

### Time-Bound Policy

| Property | Default | Description |
|----------|---------|-------------|
| `crash.time.duration-seconds` | `30` | Duration in seconds |
| `crash.time.target-rps` | `10000` | Target requests per second |
| `crash.time.auto-allocate-on-deadline` | `true` | Auto-allocate on deadline |
| `crash.time.tick-millis` | `500` | Scheduler tick interval (100, 500, or 1000) |

### Realistic Policy

| Property | Default | Description |
|----------|---------|-------------|
| `crash.realistic.alloc-prob` | `0.7` | Probability of allocation (0.0-1.0) |
| `crash.realistic.alloc-size-bytes` | `1048576` | Bytes to allocate (1MB) |
| `crash.realistic.dealloc-prob` | `0.3` | Probability of deallocation (0.0-1.0) |
| `crash.realistic.dealloc-size-bytes` | `262144` | Bytes to deallocate (256KB) |

### Common Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `crash.touch-pages` | `true` | Touch allocated pages to force physical memory |
| `crash.max-retained-chunks` | `2147483647` | Maximum retained memory chunks |

### Example: Override Configuration

**Via Environment Variables:**
```bash
export crash.oom-policy=time
export crash.time.duration-seconds=60
mvn liberty:dev
```

**Via Docker:**
```bash
docker run -p 8080:8080 \
  -e crash.oom-policy=realistic \
  -e crash.realistic.alloc-prob=0.8 \
  heap-oom-liberty:latest
```

## Key Differences from Quarkus Version

1. **Dependency Injection**: Uses Jakarta CDI instead of Quarkus Arc
2. **REST Framework**: Uses Jakarta RESTful Web Services (JAX-RS) instead of Quarkus RESTEasy
3. **Configuration**: Uses MicroProfile Config instead of Quarkus Config
4. **Scheduling**: Uses Jakarta Concurrency (ManagedScheduledExecutorService) instead of Quarkus Scheduler
5. **Logging**: Uses java.util.logging instead of JBoss Logging
6. **Packaging**: WAR file instead of Quarkus uber-jar
7. **Runtime**: IBM Semeru Runtime (OpenJ9) instead of Eclipse Temurin (HotSpot)

## IBM Semeru Runtime Benefits

- **Memory Efficiency**: OpenJ9 typically uses less memory than HotSpot
- **Faster Startup**: Quicker application startup times
- **Class Data Sharing**: Improved startup and reduced memory footprint
- **Ahead-of-Time (AOT) Compilation**: Better performance characteristics
- **Enterprise Support**: IBM-backed runtime with enterprise support options

## Testing

### Test Request-Bound Policy
```bash
# Set to OOM after 10 requests
curl "http://localhost:8080/alloc/hit"
# Repeat 10 times to trigger OOM
```

### Test Time-Bound Policy
```bash
# Configure for 30-second duration
# Application will OOM after 30 seconds regardless of traffic
```

### Test Realistic Policy
```bash
# Simulates realistic allocation patterns
# May or may not OOM depending on probability settings
```

## Troubleshooting

### Heap Dumps
Heap dumps are automatically created on OOM at `/dumps/` directory.

### View Logs
```bash
# Docker
docker logs <container-id>

# Kubernetes
kubectl logs -f deployment/heap-oom-liberty
```

### Adjust Memory Limits
```bash
# Docker
docker run -p 8080:8080 -e JAVA_OPTS="-Xmx256m" heap-oom-liberty:latest

# Kubernetes - edit manifests/deployment.yaml
resources:
  limits:
    memory: "512Mi"
```

## License

Same as original Quarkus version.

## Original Source

Converted from: https://github.com/causaai/chaos-lab/tree/main/heap-oom