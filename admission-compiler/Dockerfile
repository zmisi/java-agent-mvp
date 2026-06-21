FROM python:3.12-slim

WORKDIR /app

COPY admission-compiler/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY admission-compiler/app ./app
COPY admission-compiler/src ./src
COPY src/main/resources/admission-ontology ./ontology
COPY admission-compiler/schema ./schema

EXPOSE 8090

CMD ["uvicorn", "app.main:app", "--app-dir", ".", "--host", "0.0.0.0", "--port", "8090"]
