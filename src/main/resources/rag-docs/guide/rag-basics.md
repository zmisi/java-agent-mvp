# RAG Basics

RAG means Retrieval-Augmented Generation. A RAG application first retrieves
relevant knowledge from a document collection, then asks a large language model
to answer with that retrieved context.

The basic flow is:

1. Split source documents into smaller chunks.
2. Use an embedding model to convert each chunk into a vector.
3. Store the chunks and vectors in a vector store.
4. Convert the user question into a vector at query time.
5. Search the vector store for similar chunks.
6. Send the question and retrieved chunks to the chat model.

RAG is useful when the model needs fresh, private, or domain-specific knowledge.
It is often a better first step than fine-tuning because you can update the
knowledge base by changing documents instead of retraining a model.

RAG does not guarantee a correct answer by itself. Retrieval quality, document
chunking, source quality, and prompt design all affect the final result. If the
right chunk is not retrieved, the model may miss the answer.

For a beginner demo, an in-memory vector store is enough. For production, use a
persistent vector database such as pgvector, Milvus, Elasticsearch, Pinecone, or
another store supported by Spring AI.
