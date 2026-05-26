#!/usr/bin/env node
import { fileURLToPath } from "node:url";
import { dirname } from "node:path";
import { startServer } from "../src/index.js";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
process.chdir(root);
await startServer();
