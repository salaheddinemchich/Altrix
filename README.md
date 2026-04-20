# PubSub to Kafka Migrator

A high-performance, resilient platform designed to migrate real-time message streams from Google Cloud Pub/Sub to Apache Kafka. Built using **Hexagonal Architecture** (Ports and Adapters) to ensure domain logic remains decoupled from infrastructure.

## 🚀 Features
*   **Multi-module Gradle Setup**: Clear separation between Common, Project Management, and Orchestration.
*   **Hexagonal Architecture**: Core domain logic is protected from external changes.
*   **LTS Infrastructure**: Hardened Docker stack using stable Long-Term Support versions.
*   **Real-time Monitoring**: Built-in UIs for Kafka, Postgres, and Redis.

## 🛠 Tech Stack
*   **Language**: Java 21 (LTS)
*   **Framework**: Spring Boot 3.4.x
*   **Build Tool**: Gradle 9.4.1
*   **Database**: PostgreSQL 16 (Persistence)
*   **Messaging**: Apache Kafka 3.9 (Target)
*   **Cache**: Redis 7.2 (Job state management)
*   **Storage**: MinIO (Large payload archiving)

## 🚦 Getting Started

### Prerequisites
*   **JDK 21** (Managed via SDKMAN! recommended)
*   **Docker & Docker Compose**
*   **Gradle 9.4.1** (Included via `./gradlew`)

### Infrastructure Setup
1. Create your environment file:
   ```zsh
   cp .env.example .env # Ensure you fill in your secrets
