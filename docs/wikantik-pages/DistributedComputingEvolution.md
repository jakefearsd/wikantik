---
type: article
cluster: technology
status: active
summary: Thirty years of distributed computing — from CORBA and client-server to cloud-native
  microservices, event-driven architecture, and modern best practices for reliability,
  observability, and scale
date: '2026-03-20'
tags:
- distributed-systems
- cloud-native
- microservices
- architecture
- technology
- best-practices
related:
- TheFutureOfMachineLearning
- LlmsSinceTwentyTwenty
- EmbeddedAiOnLimitedHardware
- FoundationalAlgorithmsForComputerScientists
---
# Distributed Computing Evolution

## Three Decades of Transformation

The landscape of distributed computing has undergone radical transformation since the mid-1990s. What began with simple client-server architectures and CORBA has evolved through service-oriented architecture, cloud computing, and into today's world of globally distributed, event-driven microservices running on Kubernetes.

## The 1990s: Foundations

The 1990s established the building blocks of modern distributed systems:

- **CORBA and RPC** dominated enterprise computing, offering location-transparent remote procedure calls with IDL-based contracts. The complexity overhead was enormous, but the ideas of interface contracts and service discovery were foundational.
- **Java RMI and EJB** simplified distributed object computing for Java shops, though the deployment ceremony of EJB was notoriously painful.
- **The CAP theorem** (1998, formally proved 2002) gave the field its most important theoretical constraint: in a distributed system, you can have at most two of Consistency, Availability, and Partition tolerance. This insight continues to drive architectural decisions today.
- **Two-phase commit** was the gold standard for distributed transactions, trading availability for strong consistency.

## The 2000s: Services and Scale

The 2000s brought a fundamental shift toward loosely-coupled services:

- **SOA and web services** (SOAP, WSDL) replaced tight CORBA coupling with XML-based messaging. Verbose but interoperable.
- **REST** emerged as the pragmatic alternative, using HTTP verbs and JSON instead of SOAP envelopes. Roy Fielding's 2000 dissertation gave the architectural style its formal definition.
- **MapReduce and Hadoop** (2004-2006) democratized large-scale data processing. Google's papers on GFS, MapReduce, and Bigtable created the blueprint for an entire industry.
- **Amazon's Dynamo paper** (2007) introduced eventually-consistent key-value stores, directly inspiring Cassandra, Riak, and DynamoDB.
- **Message queues** matured: RabbitMQ (2007) and the rise of publish-subscribe patterns moved systems toward asynchronous, event-driven communication.

## The 2010s: Cloud-Native and Microservices

The 2010s saw distributed computing become the default architecture:

- **Microservices** replaced monoliths as the dominant architectural pattern. Netflix, Amazon, and Uber demonstrated that independently deployable services could scale organizations as well as systems.
- **Docker** (2013) and **Kubernetes** (2014) standardized deployment. Containers solved "works on my machine" and K8s solved orchestration at scale.
- **Apache Kafka** (2011) became the backbone of event-driven architectures, providing durable, ordered, replayable event logs.
- **Service mesh** (Istio, Linkerd) abstracted networking concerns — retries, circuit breaking, mutual TLS — out of application code and into infrastructure.
- **Consensus protocols** matured: Raft (2013) made Paxos accessible, powering etcd, Consul, and CockroachDB.
- **CRDTs** (conflict-free replicated data types) offered eventual consistency without coordination, gaining adoption in collaborative editing and distributed databases.

## The 2020s: Current Best Practices

Modern distributed systems combine lessons from three decades of evolution:

### Architecture
- **Event-driven microservices** with clear bounded contexts remain the dominant pattern for complex domains. Use domain-driven design to find service boundaries.
- **Cell-based architecture** provides blast-radius isolation — failures in one cell don't cascade across the system. AWS and Azure use this internally.
- **Edge computing** pushes logic closer to users via CDN workers (Cloudflare Workers, AWS Lambda@Edge), reducing latency for global applications.

### Data
- **Event sourcing and CQRS** separate read and write models, allowing independent scaling and providing a complete audit trail.
- **NewSQL databases** (CockroachDB, Spanner, TiDB) offer distributed SQL with strong consistency, removing the old "NoSQL or SQL" false dichotomy.
- **Stream processing** (Kafka Streams, Flink, Materialize) enables real-time analytics and derived views from event logs.

### Reliability
- **Observability** (not just monitoring): structured logging, distributed tracing (OpenTelemetry), and metrics form the three pillars. You cannot debug a distributed system without them.
- **Chaos engineering** (pioneered by Netflix's Chaos Monkey) is now standard practice for validating fault tolerance.
- **Circuit breakers and bulkheads** prevent cascade failures. Libraries like Resilience4j make these patterns accessible.
- **Zero-trust networking** replaces perimeter security with per-request authentication and encryption (mTLS via service mesh).

### Operations
- **GitOps** (ArgoCD, Flux) treats infrastructure as code with git as the single source of truth.
- **Platform engineering** provides self-service internal developer platforms, reducing the cognitive load of operating distributed systems.
- **FinOps** emerged as cloud costs became a primary concern — cost-aware architecture is now a design constraint, not an afterthought.

## Lessons Learned

Thirty years of distributed computing have taught us:

1. **Distributed systems are fundamentally harder** than centralized ones. Don't distribute unless you must.
2. **Network partitions happen.** Design for them. The CAP theorem is not optional.
3. **Eventual consistency is usually good enough.** Strong consistency has a steep cost in availability and latency.
4. **Observability is not optional.** If you can't trace a request across services, you can't debug production.
5. **Simplicity wins.** The most reliable distributed systems are the ones with the fewest moving parts.

## See Also

- [Machine Learning](TheFutureOfMachineLearning) — AI workloads drive some of the largest distributed systems today
- [Large Language Models](LlmsSinceTwentyTwenty) — LLM training and inference are distributed computing challenges at extreme scale
- [Embedded AI](EmbeddedAiOnLimitedHardware) — edge computing meets AI on constrained hardware
- [Foundational Algorithms](FoundationalAlgorithmsForComputerScientists) — consensus protocols, distributed hash tables, and the theory behind the practice
