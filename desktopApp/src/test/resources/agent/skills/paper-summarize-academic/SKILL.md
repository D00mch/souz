---
name: paper_summarize
description: Academic paper summarization with dynamic SOP selection based on paper topic classification. Supports method, dataset, multimodal, and other paper types with rigorous analysis templates.
author: Claude (克劳德)
version: 1.0.1
---

# Paper Summarize Skill

This skill provides academic-grade paper summarization with dynamic Standard Operating Procedure (SOP) selection based on paper topic classification.

## Capabilities

- **Dynamic SOP Selection**: Automatically selects appropriate analysis template based on paper type (method, dataset, multimodal, etc.)
- **Rigorous Analysis**: Follows top-tier conference review criteria (NeurIPS/ICML/ICLR/ACL)
- **Structured Output**: Generates comprehensive summaries with methodology critique, experimental assessment, strengths/weaknesses
- **Local File Storage**: Saves summaries to organized directory structure with proper naming
- **Prompt Tracking**: Maintains record of actual prompts used for reproducibility
- **Dataset Focus**: Explicit attention to training/evaluation datasets used in experiments

## Supported Paper Types

- `method`: Algorithm/architecture papers
- `dataset`: Dataset/benchmark papers  
- `multimodal`: Cross-modal learning papers
- `tech_report`: System/model release papers
- `application`: Applied AI papers
- `survey`: Survey/review papers
- `rl_alignment`: RL/Alignment/Safety papers
- `speech_audio`: Speech/audio processing papers
- `benchmark`: Evaluation/benchmark papers
- `analysis`: Empirical analysis papers

## Usage

### Input Requirements
- Paper title, authors, abstract
- Topic classification (one of supported types)
- Research context (keywords, subtopics)

### Output Format
- **Local file**: `{paper_title}.md` in `research/{domain}/ai_summaries/`
- **Content structure**:
  - Paper information (title, authors, venue, links)
  - Core contribution summary
  - Methodology critique (2000+ words)
  - Experimental assessment (1000+ words, with dataset focus)
  - Strengths and weaknesses
  - Critical questions for authors
  - Impact assessment

### Quality Standards
- **Methodology Critique**: 2000+ characters, deep technical analysis including pipeline, novelty, mathematical principles, assumptions, prior art comparison, computational cost, and failure modes
- **Experimental Assessment**: 1000+ characters, rigorous evaluation with explicit focus on **datasets used for training and testing**, protocol rigor, baseline fairness, ablation completeness, and statistical significance
- **Overall Analysis**: 3000+ characters, critical perspective
- **Technical Precision**: Correct terminology, specific method names, exact metrics

## Workflow Integration

This skill integrates with the broader research workflow:

1. **Paper Discovery**: Works with arXiv search results
2. **Quality Filtering**: Processes papers that pass relevance screening  
3. **Batch Processing**: Can be called repeatedly for multiple papers
4. **Report Generation**: Outputs feed into final research report

## Configuration

SOP templates are defined in:
- `src/lib/agents/topic-sops.ts` (primary location)
- `summarization_prompt.ts` (backup/reference)

Both files contain identical SOP definitions with shared output format requirements.

## Examples

```bash
# Summarize a method paper
paper_summarize --title "SongEcho: Cover Song Generation" --topic "method" --abstract "..." --authors "..."

# Summarize a dataset paper  
paper_summarize --title "MusicSem: Language-Audio Dataset" --topic "dataset" --abstract "..." --authors "..."
```

## Files Created

- `research/{domain}/ai_summaries/{paper_title}.md`
- `research/{domain}/prompts/{paper_title}_prompt.txt`
- Directory structure automatically created if missing