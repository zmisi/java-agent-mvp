# Spring AI RAG

Spring AI provides the building blocks for RAG in Java applications.

Important concepts:

- `Document` stores text and metadata such as the source file name.
- `TokenTextSplitter` splits long documents into smaller chunks.
- `EmbeddingModel` creates vector representations of chunks and questions.
- `VectorStore` stores vectors and performs similarity search.
- `SimpleVectorStore` is an in-memory vector store suitable for demos.
- `QuestionAnswerAdvisor` retrieves context from a vector store and adds it to a
  `ChatClient` prompt.

In this demo, the application loads Markdown files from `src/main/resources/rag-docs`.
Each file is converted into a `Document`, split into chunks, embedded by the
DashScope embedding model, and stored in `SimpleVectorStore`.

When a user asks a question, the RAG service searches the vector store for the
most relevant chunks. The service passes the same vector store to
`QuestionAnswerAdvisor`, which augments the chat prompt with retrieved context.

This demo intentionally avoids file upload, crawlers, background indexing, and
external vector databases so that the core RAG flow is easy to inspect.
