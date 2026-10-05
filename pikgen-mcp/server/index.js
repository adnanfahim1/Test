#!/usr/bin/env node
// PikGen MCP server for Claude Desktop.
// Claude Desktop runs this on your PC. Claude writes the prompts, Higgsfield's official
// connector generates (using your Higgsfield plan credits), and PikGen handles your files.
//
// Usage: node server/index.js <allowed folder> [<allowed folder> ...]

import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { Folders } from './lib/folders.js';
import { PikGen } from './lib/core.js';

const folders = new Folders(process.argv.slice(2).filter((a) => a && !a.startsWith('${')));
const pikgen = new PikGen({ folders });

const INSTRUCTIONS = `PikGen bulk-generates images/videos with the Higgsfield connector and saves them on the user's PC.
Division of work: YOU write the prompts (no API needed). The Higgsfield connector generates (it spends the user's Higgsfield credits). PikGen tools handle local files, uploads, saving and manifest.csv.

Workflow:
1. Agree on: image or video, model, how many, the idea/rules, optional pictures folder, output folder. Folders must be inside PikGen's allowed folders.
   - Pick a model with Higgsfield models_explore (input:"image" when the user's pictures are used); read its aspect_ratios, parameters and medias[].roles.
2. Write all prompts yourself: each different, standalone, following the user's variation style and rules. Show a sample and the total.
   - Get the cost with ONE Higgsfield generate_image/generate_video call with get_cost:true, multiply, and get the user's OK before spending credits. Start with a small test (1-5) if the user hasn't run this model before.
3. pikgen_start_batch with every item (number, prompt, reference_image path if any). Keep the returned batch_folder.
4. Pictures: pikgen_list_images -> Higgsfield media_upload with files[] (max 20 per call) -> pikgen_upload_files (path + upload_url + media_id) -> Higgsfield media_confirm (media_ids, type "image" or "video"). Use the media_id as medias[].value with the model's role.
5. Generate in groups of at most 12: generate_image_batch / generate_video_batch with index = item number. Immediately call pikgen_record_jobs with the returned job ids (and errors for rejected items).
6. jobs_wait on that group until all_terminal, then pikgen_save_results with each item's number, status and result URLs. Then the next group.
7. Never resubmit an item that has a job_id until its outcome is known. To resume: pikgen_batch_status, then jobs_wait the submitted_not_saved jobs, then continue with pending items.
8. Finish with a short summary (done/failed, folder path). Failed items can be retried on request.
Do not set use_unlim unless the user asks.`;

const server = new McpServer({ name: 'pikgen', version: '1.0.0' }, { instructions: INSTRUCTIONS });

const reply = (data) => ({ content: [{ type: 'text', text: JSON.stringify(data, null, 2) }] });
const safely = (fn) => async (args) => {
  try {
    return reply(await fn(args));
  } catch (err) {
    return { isError: true, content: [{ type: 'text', text: `PikGen error: ${err.message}` }] };
  }
};

server.registerTool(
  'pikgen_list_images',
  {
    title: 'List pictures in a folder',
    description: 'List PNG/JPG/WEBP pictures in a folder on the user\'s PC (sorted by name). Use before uploading reference pictures.',
    inputSchema: {
      folder: z.string().describe('Full folder path, e.g. C:\\Users\\me\\Pictures\\products'),
      recursive: z.boolean().optional().describe('Also look in sub-folders'),
      limit: z.number().int().min(1).max(1000).optional(),
    },
  },
  safely((a) => pikgen.listImages(a)),
);

server.registerTool(
  'pikgen_upload_files',
  {
    title: 'Upload pictures to Higgsfield',
    description: 'Upload local files to the upload links returned by Higgsfield media_upload (max 20). Only *.higgsfield.ai links are accepted. Afterwards call Higgsfield media_confirm.',
    inputSchema: {
      uploads: z
        .array(
          z.object({
            path: z.string().describe('Local file path'),
            upload_url: z.string().describe('upload_url from Higgsfield media_upload'),
            media_id: z.string().optional().describe('media_id from Higgsfield media_upload (echoed back)'),
            content_type: z.string().optional(),
          }),
        )
        .min(1)
        .max(20),
    },
  },
  safely((a) => pikgen.uploadFiles(a)),
);

server.registerTool(
  'pikgen_start_batch',
  {
    title: 'Start a batch',
    description: 'Create <output_folder>/<name>_<date>/images|videos and manifest.csv listing every item. Call once before generating.',
    inputSchema: {
      output_folder: z.string(),
      name: z.string().describe('Short batch name, e.g. summer-shoes'),
      kind: z.enum(['image', 'video']),
      model: z.string().describe('Higgsfield model id'),
      settings: z.record(z.any()).optional().describe('Settings used for every item, e.g. { aspect_ratio: "1:1" }'),
      items: z
        .array(
          z.object({
            number: z.number().int().positive().optional(),
            prompt: z.string(),
            reference_image: z.string().optional().describe('Local path of the picture used for this item'),
          }),
        )
        .min(1)
        .max(1000),
    },
  },
  safely((a) => pikgen.startBatch(a)),
);

server.registerTool(
  'pikgen_record_jobs',
  {
    title: 'Record submitted jobs',
    description: 'Save the Higgsfield job id for each item right after submitting (needed to resume safely without paying twice). Pass error instead of job_id for rejected items.',
    inputSchema: {
      batch_folder: z.string(),
      jobs: z.array(z.object({ number: z.number().int(), job_id: z.string().optional(), error: z.string().optional() })).min(1),
    },
  },
  safely((a) => pikgen.recordJobs(a)),
);

server.registerTool(
  'pikgen_save_results',
  {
    title: 'Save finished results',
    description: 'Download finished results into the batch folder with proper names and update manifest.csv. Pass each item\'s status from jobs_wait and its result URLs.',
    inputSchema: {
      batch_folder: z.string(),
      results: z
        .array(
          z.object({
            number: z.number().int(),
            status: z.string().describe('completed, failed, nsfw, canceled ...'),
            urls: z.array(z.string()).optional().describe('Result file URLs (https)'),
            job_id: z.string().optional(),
            error: z.string().optional(),
          }),
        )
        .min(1),
    },
  },
  safely((a) => pikgen.saveResults(a)),
);

server.registerTool(
  'pikgen_batch_status',
  {
    title: 'Batch status',
    description: 'Counts plus which items are pending, submitted but not saved (with job ids), or failed. Use to resume or retry.',
    inputSchema: { batch_folder: z.string() },
  },
  safely((a) => pikgen.batchStatus(a)),
);

server.registerTool(
  'pikgen_list_batches',
  {
    title: 'List batches',
    description: 'List PikGen batches in an output folder, newest first.',
    inputSchema: { output_folder: z.string() },
  },
  safely((a) => pikgen.listBatches(a)),
);

server.registerPrompt(
  'bulk_generate',
  {
    title: 'PikGen: bulk generate',
    description: 'Bulk-generate images or videos with Higgsfield and save them to a folder.',
    argsSchema: {
      idea: z.string().describe('What to make, e.g. "red sneaker product shots on sand"'),
      count: z.string().optional().describe('How many (default 10)'),
      kind: z.string().optional().describe('image or video'),
      pictures_folder: z.string().optional().describe('Folder with your reference pictures (optional)'),
      output_folder: z.string().optional().describe('Where to save'),
    },
  },
  (a) => ({
    messages: [
      {
        role: 'user',
        content: {
          type: 'text',
          text: `Use PikGen and Higgsfield to bulk-generate.
Idea: ${a.idea}
How many: ${a.count || '10'}
Type: ${a.kind || 'image'}
Reference pictures folder: ${a.pictures_folder || 'none'}
Output folder: ${a.output_folder || 'ask me'}
Follow the PikGen workflow. Confirm the model, a sample of prompts and the cost with me before generating.`,
        },
      },
    ],
  }),
);

await server.connect(new StdioServerTransport());
