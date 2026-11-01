# Paper Summarize Skill

Academic paper summarization with dynamic SOP selection based on paper topic classification.

## Directory Structure

```
paper_summarize/
├── SKILL.md          # Skill definition and usage
├── README.md         # This file
├── scripts/          # Executable scripts (if any)
└── templates/        # SOP templates and prompt files
```

## Integration

This skill is designed to work within the broader music generation research workflow:

1. **Input**: Papers that have passed quality filtering
2. **Processing**: Applies appropriate SOP template based on topic classification
3. **Output**: Saves detailed summaries to organized local directory structure
4. **Tracking**: Maintains prompt records for reproducibility

## Usage in Research Workflow

When processing papers from arXiv search results:

```bash
# Example workflow integration
for paper in filtered_papers:
    topic = classify_paper(paper)  # method, dataset, multimodal, etc.
    summary = paper_summarize(
        title=paper.title,
        authors=paper.authors, 
        abstract=paper.abstract,
        topic=topic,
        domain="music_generation"
    )
    save_to_file(summary, f"research/music_generation/ai_summaries/{paper.title}.md")
```

## Quality Assurance

- All summaries follow rigorous academic standards
- Methodology critique receives extra emphasis (1500+ characters minimum)
- Critical perspective maintained throughout (not just paper restatement)
- Technical precision with correct terminology and specific references