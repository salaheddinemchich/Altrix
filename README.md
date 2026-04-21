# PubSub → Kafka Migrator

An AI-powered platform that automatically migrates Java Spring Boot applications
from Google Cloud PubSub to Apache Kafka. Upload a ZIP, get back a migrated ZIP.

## What it does (MVP)

1. User uploads a ZIP of a Spring Boot + PubSub project
2. Platform detects: build system (Maven / Gradle), framework (Spring Boot / Jakarta EE), config format (YAML / Properties)
3. Agent 1 reads all Java files and maps every PubSub component (@PubSubListener, PubSubTemplate, subscriptions, topics)
4. Agent 3 rewrites those files to use the Kafka equivalent (@KafkaListener, KafkaTemplate, consumer groups)
5. User downloads the migrated ZIP — config format preserved, build system preserved

## Architecture

Built with Hexagonal Architecture (Ports & Adapters) + CQRS + DDD across independent microservices.

Upload ZIP → platform-project → platform-job → platform-orchestrator → Download ZIP
↕                    ↕
Kafka              AI (Groq)
## Tech Stack

| Concern       | Technology              | Version   |
|---------------|-------------------------|-----------|
| Language      | Java                    | 21 LTS    |
| Framework     | Spring Boot             | 3.3.4     |
| Build         | Gradle Kotlin DSL       | 9.4.1     |
| Database      | PostgreSQL              | 16.8      |
| Messaging     | Apache Kafka            | 3.9.0     |
| Cache         | Redis                   | 7.2.7     |
| Storage       | MinIO                   | 2024-11   |
| AI Provider   | Groq (Llama 3.3 70B)    | free tier |

## Quick Start

```zsh
# 1. Clone and enter
git clone https://github.com/salaheddinemchich/pubsub-kafka-migrator.git
cd pubsub-kafka-migrator

# 2. Create your local environment file
cp .env.example .env
# Edit .env — fill in your passwords and Groq API key

# 3. Start the full infrastructure stack
docker compose up -d

# 4. Verify everything is healthy
docker compose ps
```

## Service ports (dev)

| Service         | URL                          |
|-----------------|------------------------------|
| platform-project | http://localhost:8082        |
| platform-job     | http://localhost:8083        |
| platform-orchestrator | http://localhost:8084   |
| pgAdmin          | http://localhost:5050        |
| Redis Insight    | http://localhost:8001        |
| Kafdrop (Kafka)  | http://localhost:9002        |
| MinIO Console    | http://localhost:9001        |

## Get a free Groq API key

Go to https://console.groq.com → create account → API Keys → Create key.
Add it to your `.env` as `AI_PROVIDER_API_KEY`.
