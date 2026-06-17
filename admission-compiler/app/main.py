import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

from fastapi import FastAPI, HTTPException

from admission_compiler.compiler import AdmissionQueryCompiler
from admission_compiler.ir import CompileRequest, CompileResponse

app = FastAPI(title="Admission Query Compiler", version="1.0.0")
_compiler = AdmissionQueryCompiler()


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/compile", response_model=CompileResponse)
def compile_query(request: CompileRequest) -> CompileResponse:
    if not request.message or not request.message.strip():
        raise HTTPException(status_code=400, detail="message is required")
    return _compiler.compile(request)


@app.post("/v1/compile", response_model=CompileResponse)
def compile_query_v1(request: CompileRequest) -> CompileResponse:
    return compile_query(request)
