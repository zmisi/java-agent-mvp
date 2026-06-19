FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY src ./src
COPY ontology ./ontology
COPY schema ./schema

EXPOSE 8090

CMD ["uvicorn", "app.main:app", "--app-dir", ".", "--host", "0.0.0.0", "--port", "8090"]
