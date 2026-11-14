import 'dotenv/config';
import { buildApp } from './app.js';
const app = await buildApp();
await app.listen({ port: Number(process.env.PORT) || 3000, host: '0.0.0.0' });
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, async () => { await app.close(); process.exit(0); });
