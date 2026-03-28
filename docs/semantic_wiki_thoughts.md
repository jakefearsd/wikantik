# **Evolutionary Architectures for Personal Knowledge Management: Integrating Semantic Web Ontologies and Agentic AI through the Model Context Protocol**

> **Note:** This document captures aspirational research on semantic wiki capabilities. Some concepts described here (frontmatter metadata, content clusters, MCP integration) have been partially implemented. Others (RDF triplestores, SPARQL endpoints) remain future possibilities.

## **The Paradigm Shift in Cognitive Externalization**

The history of personal knowledge management (PKM) has reached a critical inflection point where the traditional barriers between human cognition and digital storage are dissolving. Historically, knowledge workers have relied on fragmented systems—ranging from simple text files to sophisticated hierarchical wikis—to archive their intellectual output. However, these systems have largely remained passive repositories, functioning as digital "graveyards" where information is stored but rarely synthesized or proactively retrieved. The emergence of large language models (LLMs) initially promised to solve this by providing a conversational interface to data, yet early implementations were hampered by the isolation of models from the user’s private, high-fidelity context. This isolation resulted in a "copy and paste tango," where users manually fed fragments of their history into AI interfaces to receive relevant responses.

The convergence of the Model Context Protocol (MCP) and semantic web technologies represents a fundamental shift toward the "Semantic Agentic Web." This new paradigm moves beyond the academic ideal of a top-down, machine-readable internet, instead focusing on a pragmatic, bottom-up approach where machines are taught to read existing data through standardized interfaces. By exposing a local wiki to an AI via an MCP server, the wiki is transformed from a static archive into a dynamic, "living" participant in the research process. This architecture enables a recursive feedback loop in which the AI not only retrieves information but also curates, tags, and expands the knowledge base with the depth of a domain expert.

The current technological landscape, particularly with the introduction of the MCP by Anthropic in late 2024, provides the "USB-C port" for AI integration. This standardized protocol allows LLMs to interact with local files, databases, and digital artifacts with deterministic precision, bypassing the limitations of purely vector-based retrieval augmented generation (RAG). When coupled with the Resource Description Framework (RDF) and formal ontologies, this creates a system capable of managing complex relationships across documents, photos, and activity logs, effectively functioning as a "digital twin" of the user’s intellectual life.

## **Foundations of Semantic Knowledge Representation**

To build an automated wiki that functions as a research partner, one must first establish a robust layer of formal semantics. Traditional wikis rely on keyword matching and internal hyperlinking, which lacks the granularity required for an AI to perform complex reasoning. Semantic wikis, however, treat every page and artifact as a resource within an RDF graph. This graph-based approach captures relationships between concepts through triples (subject-predicate-object), allowing the system to understand that "John Grisham" is not just a string of text, but a resource of type Author who wrote the resource The Firm.

### **Comparison of Knowledge Management Systems**

| Feature | Traditional Wiki | Semantic Wiki | AI-Augmented Semantic Wiki (Agentic) |
| :---- | :---- | :---- | :---- |
| **Data Structure** | Unstructured Text / Hyperlinks | RDF Triples / Structured Metadata | Dynamic Knowledge Graph / Agentic Context |
| **Search Mechanism** | Keyword / Full-text | SPARQL / Faceted Browsing | Semantic Reasoning / MCP-Driven Tools |
| **Content Creation** | Manual | Manual / Template-based | Autonomous / Agent Skill-driven |
| **Interoperability** | Low (Proprietary/HTML) | High (Linked Data Standards) | Universal (MCP / JSON-RPC) |
| **Update Cycle** | Static / Manual | Query-based Dynamic Views | Recursive Feedback Loop |

The transition from a taxonomy (a simple hierarchy of categories) to an ontology (a rich set of defined relationships) is essential for machine consumption. While humans can discern context through intuition, AI models require explicit definitions to differentiate between nuanced concepts, such as the difference between a "Project" and an "Area of Responsibility" in the P.A.R.A. framework. Ontologies like the CIDOC Conceptual Reference Model (CIDOC-CRM) or the LODE ontology provide the necessary terminology to describe events, people, and objects in a way that is interoperable across different systems.

### **The Role of RDF and SPARQL in Personal Research**

RDF serves as the foundational data model for the semantic wiki, enabling the representation of heterogeneous data—documents, emails, and sensor logs—in a unified format. Every digital artifact in the PKM system is assigned a Unique Resource Identifier (URI), making it addressable by the AI. SPARQL (SPARQL Protocol and RDF Query Language) provides the mechanism for the AI to "ask" the wiki complex questions that keyword search cannot answer, such as "Find all research papers cited in my 2023 logs that discuss transformer efficiency and have associated hand-written notes."

Integrating these semantic concepts into a local wiki often involves a "Content Store" or triplestore that interacts with the page server. When a user or an AI agent creates a new Markdown page, a parser extracts the semantic annotations and updates the central knowledge graph. This ensures that the AI’s "memory" of the user’s knowledge is always in sync with the actual contents of the wiki.

## **The Model Context Protocol: The Architectural Gateway**

The Model Context Protocol (MCP) is the technical bridge that allows an AI host to safely and efficiently interact with a local semantic wiki. Prior to MCP, connecting an AI to a personal database required custom-built "glue code" for every specific model and data source, leading to "integration hell." MCP standardizes this connection using a client-server architecture inspired by the Language Server Protocol (LSP) used in modern development environments.

### **MCP Core Primitives and Wiki Integration**

The protocol relies on three primary building blocks that define how an AI perceives and manipulates the wiki:

1. **Resources:** These are passive data sources that the AI can read to gain context. In a wiki system, resources include page content (converted to Markdown), metadata files, database schemas, or even raw logs. Resources are URI-addressable, allowing an agent to fetch specific data points, such as wiki://metadata/project-alpha/timeline.  
2. **Tools:** These are executable functions that the AI can invoke to perform actions and trigger side effects. For a PKM system, tools might include create\_wiki\_page, update\_rdf\_triple, search\_git\_history, or process\_image\_metadata. Unlike resources, tools represent agency; the AI decides when to call them based on the user’s request.  
3. **Prompts:** These are pre-built instruction templates provided by the MCP server. They guide the model on how to interact with the specific data domain. For example, a "Research Summary" prompt might instruct the AI on the required Markdown tags and YAML frontmatter structure needed to maintain semantic consistency in the wiki.

| MCP Component | Implementation in a Local Wiki | Benefits for Research Depth |
| :---- | :---- | :---- |
| **Host** | AI Application (e.g., Claude Desktop) | Centralized interface for interaction |
| **Client** | Protocol Connector within the Host | Handles auth and message routing |
| **Server** | Local Wiki MCP Server | Exposes wiki data and tools safely |
| **Transport Layer** | STDIO or HTTP+SSE | Enables real-time, bidirectional sync |

### **Transport Mechanisms and Message Loop**

The communication between the AI and the wiki occurs through JSON-RPC 2.0 messages. For local implementations, the protocol typically uses Standard Input/Output (STDIO), where the server runs as a background process on the user’s machine. This ensures that the data remains local and secure. The "message loop" involves the model identifying a need for a tool, the host sending a request to the MCP server, and the server returning structured results to be injected into the model’s reasoning process. This cycle allows the AI to "think" using the wiki as its external memory.

## **Markdown as the Spec-Driven Interface for Agentic PKM**

Markdown has evolved from a simple documentation format into an "instruction layer" that governs AI behavior. In an agentic wiki, Markdown serves two roles: it is the human-readable content of the wiki and the machine-readable specification for the AI’s actions. This "Programming in Markdown" approach allows users to define AI rules, workflows, and reusable prompts within the same repository as their research notes.

### **Semantic Markdown and YAML Frontmatter**

To create a feedback loop, the Markdown articles produced by the AI must be "appropriately tagged" with semantic data. This is typically achieved through YAML frontmatter, which sits at the top of a Markdown file between triple-dashed lines (---).

| Metadata Field | RDF Equivalent | Role in the Feedback Loop |
| :---- | :---- | :---- |
| type: | rdf:type | Defines the ontological class (e.g., ResearchArticle) |
| mentions: | schema:mentions | Establishes links to other wiki entities or resources |
| source: | dc:source | Tracks the provenance of the research data |
| reliability: | hico:hasProvenance | Permits the AI to weigh the data's credibility |
| status: | p-plan:Variable | Indicates the workflow state (e.g., draft, reviewed) |

The mapping of this YAML data to RDF triples is handled by the MCP server, often using tools like YARRRML or SPARQL Anything, which can transform structured text into a queryable graph. This ensures that when the AI writes a new article, it isn't just creating a text file; it is adding a new node and set of edges to the user’s intellectual landscape.

### **Anthropic Agent Skills and SKILL.md**

A critical component of the user's request is the "research skill" for Claude. Anthropic’s Agent Skills framework allows users to package domain expertise into a folder containing a SKILL.md file. This file acts as a recipe card that teaches the AI how to perform specific tasks, such as conducting deep research or maintaining the wiki’s taxonomy.

The SKILL.md structure uses "progressive disclosure," a three-level loading system to optimize context window efficiency:

1. **Metadata:** The skill's name and description are always loaded into the system prompt. This allows the AI to know that a "Research Skill" is available without consuming excessive tokens.  
2. **Instructions:** The body of the SKILL.md is loaded only when the AI decides the skill is relevant to the user’s query. This contains the detailed workflows, style guides, and best practices for creating wiki content.  
3. **Use:** The AI follows the instructions, accessing helper files or executing scripts to fulfill the task.

## **Engineering the Research Feedback Loop**

The "feedback loop" described in the conceptual design is an iterative process where the wiki provides context to the AI, and the AI’s output refines and expands the wiki. This loop is essential for developing content at depth, as it prevents the AI from becoming a mere summary tool and instead makes it a proactive research assistant.

### **Mechanism of the Feedback Loop**

1. **Context Injection:** When the user initiates a research task, the AI host uses the MCP server to retrieve relevant nodes from the knowledge graph. This includes past notes, related artifacts, and the user’s established preferences.  
2. **Information Retrieval (RAG \+ Semantic Search):** The AI uses its "Research Skill" to search the web or external databases. It uses its understanding of the user’s existing ontology to filter for results that are truly novel or relevant.  
3. **Synthesis and Annotation:** The AI generates a new Markdown article. Crucially, it uses its "Research Skill" to automatically apply YAML tags that link the new content to existing wiki entities.  
4. **Verification and Validation:** The AI can execute code (via MCP) to verify links, check for contradictions in the knowledge graph, or validate the structured data against the project’s ontology.  
5. **Wiki Integration:** The user reviews the content, and upon approval, the MCP server writes the file and updates the triplestore. The new knowledge is now part of the context for all future interactions.

### **Mathematical Modeling of the Knowledge Graph Update**

The effectiveness of this loop can be understood through Graph Neural Network (GNN) principles. Each research step updates the feature representation of the knowledge nodes. The state update for a node ![][image1] after an AI-assisted research cycle is defined as:

![][image2]  
where ![][image3] represents the adjacency matrix of the wiki's knowledge graph, and ![][image4] represents the AI's "learned" understanding of the research context. By iteratively updating this graph, the system moves toward a more accurate and comprehensive representation of the user's expertise.

## **Conceptual Design: The Semantic Agentic Wiki Architecture**

The proposed system architecture is designed to be local-first, privacy-preserving, and highly extensible. It decouples the user interface from the reasoning engine and the data storage, allowing each to be swapped or upgraded independently.

### **Layered System Components**

| Layer | Component | Technical Specification |
| :---- | :---- | :---- |
| **User Interface** | Personal Wiki / Editor | Obsidian, VS Code, or MediaWiki |
| **Logic Layer** | AI Host | Claude Desktop or an AI-powered IDE (e.g., Cursor) |
| **Protocol Layer** | MCP Client/Server | JSON-RPC 2.0 over STDIO |
| **Data Layer** | Knowledge Graph | RDF Triplestore (Neo4j, Apache Jena, or Oxigraph) |
| **Storage Layer** | File System | Local Markdown files and binary attachments |

### **The "Research Skill" Specification**

The user’s research skill is defined in .claude/skills/deep-research/SKILL.md. It contains the following high-level directives:

* **Discovery:** Use the list\_wiki\_pages and execute\_sparql\_query tools to map existing knowledge related to the prompt.  
* **Expansion:** Use the fetch and web\_search tools to find external sources. Prioritize peer-reviewed literature or production-grade code.  
* **Curation:** Generate Markdown files with a specific YAML header: project, concepts, evidence\_rating, and related\_artifacts.  
* **Linking:** For every new entity discovered, check the wiki for an existing page. If it exists, link to its URI. If not, create a placeholder "stub" page with metadata.

### **Integrating Photos and Other Digital Artifacts**

Unlike textual notes, artifacts like photos and system logs are "occurrents" or "binary data attachments." The conceptual design represents these through "Media Cards" in RDF. A photo is not just a file; it is a resource with properties: lode:atPlace, lode:atTime, and mw:depicts. The AI uses vision models or EXIF extraction tools (exposed via MCP) to automatically generate these semantic descriptions, allowing the user to search their artifacts by context rather than just filename.

## **Twelve Novel Use Cases for the Semantic Agentic Wiki**

The true value of this capability lies in how it enables "wisdom synthesis"—turning a pile of notes into actionable intelligence. Beyond basic search and summarization, the following use cases demonstrate the power of a deeply linked, AI-curated knowledge base.

### **1\. Autonomous Interdisciplinary Synthesis**

The AI agent can be tasked with scanning the entire knowledge graph to identify hidden connections between unrelated domains. For instance, it might discover that a user's research on "Decentralized Finance" and their personal logs on "Mycelial Networks" share structural similarities in resilient topology. The AI can then draft a synthesis paper exploring "Biomimetic Network Resilience," citing specific notes from both categories.

### **2\. Chronological Epistemic Mapping**

Leveraging the LODE (Linking Open Descriptions of Events) ontology, the AI can reconstruct the evolution of a user's thinking on a specific topic over years. It can generate a narrative article titled "The Evolution of My Stance on AGI: 2020–2025," documenting how specific articles, emails, and life events changed the user's perspective, providing a high-fidelity "audit trail" of intellectual growth.

### **3\. Contextual Artifact Retrieval for Meeting Preparation**

By linking the wiki to a personal calendar via MCP, the AI can proactively prepare briefing documents for upcoming meetings. It doesn't just pull the meeting invite; it retrieves every related wiki note, project artifact, and even a photo of a whiteboard from a meeting with that same person three years ago, synthesizing them into a "Context-Aware Brief."

### **4\. Automated Bibliographic Provenance and Reliability Scoring**

When the user adds a new article to the wiki, the AI uses its "Research Skill" to trace the citations of that article through the user’s existing knowledge base. It assigns a "Reliability Rating" based on how well the new data aligns with previously verified information in the wiki. If a contradiction is found, the AI creates an "Epistemic Conflict" alert, prompting the user to re-evaluate the two sources.

### **5\. Semantic Legacy and Digital Estate Planning**

The AI can curate a "Legacy Vault" by identifying the most influential and deeply linked nodes in the knowledge graph. By analyzing the strength of semantic relationships, it can automatically organize a collection of the user’s most important work, personal stories, and digital artifacts, ensuring that the metadata remains intact and readable for future generations or executors.

### **6\. Predictive Habit and Cognitive Load Analysis**

By correlating research activity logs with personal health data (linked via MCP), the AI can identify patterns in the user's cognitive performance. It might observe, for example, that the user’s writing on complex technical topics is most "semantically dense" when they have had at least seven hours of sleep or after morning exercise, allowing for data-driven optimization of the research schedule.

### **7\. Generative Personal Encyclopedia (Wiki-to-Article)**

The AI can autonomously transform a collection of fragmented, non-linear notes on a specific topic into a polished, encyclopedia-style article. It uses the underlying RDF graph to ensure that every claim in the article is backed by a citation to a primary source or a personal artifact already stored in the wiki, essentially turning the PKM into a self-authoring book.

### **8\. Semantic Communication Triage**

An MCP server connected to the user’s communication channels (Slack, Email) can automatically map incoming messages to existing wiki entities. Instead of a traditional inbox, the user views their "Project Alpha" wiki page, which now includes a dynamic "Relevant Communications" section showing only the emails and messages that semantically relate to that project.

### **9\. Collaborative "Digital Twin" Research**

If two researchers use compatible semantic wiki structures, their AI agents can "negotiate" and share knowledge without the users needing to manually send files. One user's agent can query the other's "Public" MCP server for a summary of a specific topic, receiving a semantically tagged Markdown article that instantly integrates into the requester’s wiki, preserving all source metadata.

### **10\. Semantic Architecture Decision Records (ADRs) and Code Synthesis**

Architectural Decision Records (ADRs) serve as the "project constitution," documenting design choices, context, and consequences within the software engineering lifecycle. By exposing Markdown-based ADRs to an AI via MCP, the system can automatically enforce architectural consistency across a codebase. The AI agent can then ingest these locked-in decisions to autonomously generate epics, user stories, and even boilerplate code that adheres strictly to the established system boundaries and dependencies.

### **11\. Predictive Personal Finance Modeling and Risk Analysis**

Using semantic web ontologies like the Financial Industry Business Ontology (FIBO), the AI can model complex relationships between private and public companies, ownership structures, and personal debt instruments. This allows for real-time financial management where the AI automatically categorizes expenses from bank feeds and identifies anomalous spending patterns. Users can execute sophisticated "what-if" scenario planning, such as modeling the long-term impact of a $150,000 salary change or a major program investment on their future cash flow.

### **12\. Adaptive Learning Pathways and Automated Knowledge Retrieval**

Personalized Learning Path Planning (PLPP) utilizes an educational architecture to align learning materials with a user's unique professional goals. The AI agent identifies gaps in the user’s wiki by analyzing the density and connectivity of existing notes, proactively recommending new content to fill those voids. Furthermore, the system can automatically generate flashcards, summaries, or quiz questions from research articles to facilitate spaced repetition and recall. By tracking a learner's mastery via tutoring ontologies, the system adaptively redirects the research path toward more relevant or complex subjects.

## **Security, Privacy, and Trust in an Agentic PKM**

The primary challenge of an AI-driven, deeply linked wiki is ensuring the security and privacy of the underlying data. Because the system involves arbitrary code execution paths (via MCP tools) and the storage of sensitive personal data, it must be built on a foundation of "User Consent and Control."

### **Security Principles for MCP Wiki Integration**

| Principle | Technical Implementation |
| :---- | :---- |
| **Explicit Consent** | Host applications must provide clear UIs for authorizing tool execution and data access. |
| **Data Privacy** | Sensitive data (e.g., passwords, PII) should be excluded from the AI's training or sampling path. |
| **Tool Safety** | All MCP tools must be treated as untrusted code and executed in sandboxed environments. |
| **Progressive Scoping** | Permission is granted only for the specific scope (e.g., one directory) required for the task. |
| **Local-First Processing** | Prioritize local LLMs or secure transport (STDIO) over remote HTTP connections. |

The SKILL.md format, while powerful, is vulnerable to prompt injection if malicious instructions are hidden in long files or referenced scripts. This is particularly dangerous for coding agents that operate on various user files and credentials. To mitigate this, users must treat Agent Skills as executable software, auditing them for malicious behavior and using only trusted skill marketplaces.

## **Future Outlook: The Intelligence Orchestration Era**

As we move from orchestrating infrastructure (DevOps) to orchestrating intelligence (CollabOps), the role of the personal wiki will continue to expand. We are entering an era where personal knowledge management is not about "saving links" but about "building knowledge networks your AI can navigate."

The future of the semantic agentic wiki lies in its ability to become a "Self-Learning Knowledge Base." Through continuous learning from user interactions and feedback loops, the system will proactively identify knowledge gaps, suggest content updates, and even anticipate the user’s information needs before they are articulated. By integrating feedback loops and optimizing hyperparameters (e.g., for semantic search relevance), these systems will evolve into accurate, context-aware partners that are indistinguishable from a highly skilled human researcher.

Ultimately, this capability restores the "unpredictability that sustains creativity" by engineering serendipity back into hyper-personalized systems. The semantic agentic wiki is not just a tool; it is a "sensible collaborator" that allows human users to reclaim their autonomy and joyful discovery in an increasingly automated world.

## **Conclusion: Designing the Cognitive Feedback Loop**

The architecting of a semantic agentic wiki represents a significant leap forward in our ability to manage the complexity of the modern information environment. By leveraging the Model Context Protocol as a universal bridge and the semantic web as a universal language, we can create personal knowledge systems that are both deeply private and immensely capable. This framework allows for a feedback loop where the wiki becomes an active extension of the human mind—linked to our history, curious about our future, and tireless in its curation.

The implementation of such a system requires careful attention to ontological design, protocol security, and the crafting of effective agent skills. However, the reward is a "wisdom engine" that synthesizes data into insight and information into action. Whether through temporal argument reconstruction, geographic knowledge mapping, or engineered serendipity, the semantic agentic wiki empowers the individual to develop their content at a depth previously reserved for large research organizations. In this era of intelligence orchestration, the most valuable asset is no longer the data itself, but the network of relationships and the agentic capabilities that allow us to navigate it with precision and purpose.

## **Appendix: ADRs for Large-Scale Microservices Consistency**

In the context of a complex business managing thousands of microservices, Architecture Decision Records (ADRs) transition from simple text logs into a critical governance framework that prevents "architectural drift" and ensures long-term system health. This appendix outlines how the ADR use case can be operationalized to maintain consistency across a large-scale deployment.

### **Hierarchical Governance: Global vs. Local Definitions**

Large organizations often struggle with the balance between team autonomy and global standardization. A pragmatic approach utilizes a hierarchical ADR structure:

* **Global ADRs:** These establish the organizational "constitution," defining non-negotiable boundaries such as required communication protocols (e.g., mandatory asynchronous event-driven architecture), security standards (e.g., OAuth 2.0 service-to-service), and primary data modeling principles (e.g., "Database Per Service").  
* **Local ADRs:** Individual teams or domains refine these global rules within their specific context, documenting service-level design choices that must still strictly reside within the boundaries established by the global definitions.

### **Operationalizing Architecture via AI Enforcement**

Traditional ADRs often become "digital dust collectors" because they are disconnected from the build process. In an agentic environment, ADRs are treated as "Instruction by Design":

* **Machine-Consumable Metadata:** By embedding structured metadata (YAML or Semantic Markdown) into ADR files, AI Code Assistants can parse them as live directives.  
* **Real-Time Guardrails:** When a developer (human or AI agent) attempts to implement a new feature, the AI enforcement layer scans the repository's ADR log to identify potential violations. For example, if an ADR forbids direct synchronous calls between two specific domains to prevent cascading failures, the AI agent can flag a violation during the coding phase rather than at code review.

### **Centralized Discovery and Traceability**

To manage sprawl, ADRs must be integrated into a centralized discovery portal like Backstage:

* **Contextual Siting:** The Backstage ADR plugin allows records to "sit next to" the services they affect, providing instant context for engineers who are not the original authors.  
* **Searchable Decision Trails:** This creates an auditable history of why certain design choices were made, which is invaluable for onboarding new team members and conducting architectural reviews.

### **Semantic Analysis and Conflict Detection**

Leveraging knowledge graphs for ADRs enables advanced reasoning across projects:

* **Identifying Hidden Violations:** Semantic modeling allows for the automated detection of inconsistencies or "epistemic conflicts" across different projects within the same enterprise knowledge graph.  
* **Cross-Project Learning:** Architects can use SPARQL to query thousands of ADRs across the business to find successful patterns or common pitfalls (e.g., "Find all ADRs where teams reported high latency after adopting GraphQL for inter-service communication").

[image1]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABwAAAAbCAYAAABvCO8sAAAB7ElEQVR4Xs2VTSilYRTHjxARUT42PiY02ZiQLDQ+IyWllLKYspkFspkoLJA0U2pC2AvZIAs0hUhEZCxmNrY2FiIfaRZW/E/nvjr3uN3pfrzyq185/3O7z3Xe53leouDIgss2VJTBbzYMhXVYaLIa+FHVszBD1UGTDU9tCP7CclU3wSFVB003nDFZKnyA0SrLg6uqDpo52KPq7yT/3QXcgvmqx1nIbMB6k/2AAyZjDmCuDQPlFywy2RGsMhmzAmttGCiTsFnV8fAexsIUGKl6J7BU1V70wzv4BDtMT9NFsnEcPsN9z9+DMEr1/sEkVb/iK8mCH0yuqYPTqo4j2SxtsFLlafBY1T6Zh39saIiBuyQj9AdPotOGmgh4BUdtwwfVsNeGikSSHZpgG5piknHyr1qAa3AbtugPKcZgsg098DP+ZENLH8mCfCk7v4y/9PblE2FmE56RbHMHHu8NybjDCi/yCIdNzrts0WRhoZFknHymHPhK4uyLysIGP6tr8r4lfpIcXH6efHe2qh6/XMdVHTB829vRHcIlkoPNz5e3ukMJ+bmy/gcfYN4YDSbnF+geya6tML03g8c7AndILmrXaSeZyDnM8W65QybJeH/bhptMkVxdvLjr8LG5hOlwwvRcga84fovzYgWm9z55Bv+qTmg6+ViMAAAAAElFTkSuQmCC>

[image2]: <data:image/png;base64,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>

[image3]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABsAAAAYCAYAAAALQIb7AAABU0lEQVR4Xu2UPyiGURSHf5I/HxPC9NlMbMogUsrAp5QkkzLaCCkZJAZmNoOyi00ppJhMyiBGk2ySSfxO5+re93xlcV7T99RT957z9p7bvedeoEIFByboiw3mQQt9ol+0YHLuzNMDaLHObMqXcWiBHWixwWzajyY6GcaL0GLTMe3LHK0K4xlosYWY9mOYdifzEWgx2U5LP722QbJKt2zQ0kyfoT+3Hibf/dCGuN0pA7THBi27tN3EOqDFzkz8T0j3SatbGqHF7k18iZ7QoSTWRVfoJX65l9Lij7TBJgLv9C2Z90G3SbZ2NsSqERf7EPIZeukt/URcfW2SX6dXISfe0GVapPX0lbaGb2WhMpZz/6A1Ie7CFD2GnnG6wBI9T+YuHEE7cRvZ89mgm8nchT26j/KXRbp21MRyoQ7aTPLc5YZcmzU6Rk9Nzh15rO/oBcofhf/nG5ZsPIPaGOUdAAAAAElFTkSuQmCC>

[image4]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACUAAAAYCAYAAAB9ejRwAAACEElEQVR4Xu2VQUgVURSGj2mRZGEbzRZtFCt3EZFZBrYK0pDARBCkUtvYrkhDoU21UCGUoI0rKSJFEsFEEUIURHLtQoggCBeCqEQQhP3/O3fsvNPr7eat5oMP5v7Hd+/MnTNXkXjph9U+NPTA8z6Mk0twzGVFsNWMD8MpmG+yWBmBt1zWDGdcNgivuSwWDsAtWO7yl/CJy+7CRy6LhUq4K3pz5Aycgz/gInwWcnIRvjPjFPzhJPwG9+BvuARPifbALNwItW3RyQ+lfqlPyYVY2xF9FeQqXAjXEUdF/5Z9ZDkCv7hsn8eik7f7AnglWrvhC6ARjsJCk12G42ZMGkQfKBO82QIfkjuiCz/0BdEFWGtxOb8a7jJ3wVICV132HPaF61KTHxfd5YzcFF34hcvr4HCodbnaA3jbZRFsB8tHeF20+dtMfgGumHEataILvzbZQdEJmkLtqamVwQ9m7PkET5hxBxyC9yX9VXHuATNOo0p04fcm4+tir/AcYY07FvEGnjZjD9vB72wmuF6FDyP4nrlw1IwnRZuTnAs13gjhgr3hOhsTol/X/+C/oLc+tHBLufDnML5najweWJsWvfl5+XssZIM72e3DQJ7ozvuP5B824VdYA8+a/JjoTS2LTnTF1GJnDf4UbUYLn+qX6CnNMyun8BT+Dot9QfQTXxfdtZzCxuz0YYC9Vu/DhISEhJj5A8YrX51UqJA4AAAAAElFTkSuQmCC>