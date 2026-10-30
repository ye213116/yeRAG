# DeepSeek V4.1 tokenizer

The compressed `tokenizer.json.gz` is the unmodified tokenizer JSON from the
official `deepseek-ai/DeepSeek-V4.1-Flash` model, revision
`314113f8a7dadb388215396ba5fbee2c610a04d6`. It is bundled so text tokenization
requires no network access or Python service at runtime.

- Source: https://huggingface.co/deepseek-ai/DeepSeek-V4.1-Flash/blob/314113f8a7dadb388215396ba5fbee2c610a04d6/tokenizer.json
- Uncompressed SHA-256: `c90dfa01249db1be4245780a052ede752e1361c612ac6d08e2bdada7d599476b`
- Model context: 1,048,576 tokens, from the same revision's `text_config.max_position_embeddings`.
- License: MIT, included in `LICENSE` from the same revision.
- Runtime: DJL HuggingFace Tokenizers 0.39.0; automatic truncation, padding and
  special-token insertion are disabled.

The provider does not publish its complete API-side prompt construction.
yeRAG counts decoded message content, tool definitions and tool arguments with
this exact BPE vocabulary, reserving protocol overhead per message/tool and room
for output. HTTP JSON escaping is excluded from the model-token count. The returned `usage` remains authoritative; the
local count is not presented as an exact billing count. Changing the serving
model requires verifying and configuring the matching tokenizer and capacity.

The official API's generic tokenizer archive was also checked; it contains an
older tokenizer configuration, so the model's pinned tokenizer is used here.
