// Starts the real server the way Claude Desktop does (stdio) and talks MCP to it.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

test('server speaks MCP: tools, prompt, instructions, and a real tool call', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'pikgen-proto-'));
  const transport = new StdioClientTransport({ command: process.execPath, args: ['server/index.js', root] });
  const client = new Client({ name: 'test', version: '1.0.0' });
  await client.connect(transport);
  try {
    const { tools } = await client.listTools();
    assert.deepEqual(tools.map((t) => t.name).sort(), [
      'pikgen_batch_status', 'pikgen_list_batches', 'pikgen_list_images', 'pikgen_record_jobs', 'pikgen_save_results', 'pikgen_start_batch', 'pikgen_upload_files',
    ]);
    assert.match(client.getInstructions(), /Higgsfield connector/);
    const { prompts } = await client.listPrompts();
    assert.equal(prompts[0].name, 'bulk_generate');

    const res = await client.callTool({
      name: 'pikgen_start_batch',
      arguments: { output_folder: path.join(root, 'out'), name: 'proto', kind: 'image', model: 'm', items: [{ prompt: 'hello' }] },
    });
    assert.ok(!res.isError, res.content[0].text);
    const data = JSON.parse(res.content[0].text);
    await fs.access(path.join(data.batch_folder, 'manifest.csv'));

    const bad = await client.callTool({ name: 'pikgen_list_images', arguments: { folder: os.homedir() } });
    assert.equal(bad.isError, true);
  } finally {
    await client.close();
  }
});
