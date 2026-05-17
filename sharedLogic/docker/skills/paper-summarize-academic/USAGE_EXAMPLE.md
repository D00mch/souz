# Paper Summarize Skill - Usage Example

## Step 1: Classify Paper Topic
```javascript
// Example paper classification
const papers = [
  {
    title: "SongEcho: Cover Song Generation via Instance-Adaptive Element-wise Linear Modulation",
    topic: "method", // method, dataset, multimodal, etc.
    authors: "Sifei Li, Yang Li, Zizhou Wang, et al.",
    abstract: "Cover songs constitute a vital aspect of musical culture..."
  },
  {
    title: "MusicSem: A Semantically Rich Language--Audio Dataset",
    topic: "dataset",
    authors: "Rebecca Salganik, Teng Tu, Fei-Yueh Chen, et al.", 
    abstract: "We introduce MusicSem, a semantically rich language-audio dataset..."
  },
  {
    title: "Art2Mus: Artwork-to-Music Generation via Visual Conditioning",
    topic: "multimodal",
    authors: "Ivan Rinaldi, Matteo Mendula, Nicola Fanelli, et al.",
    abstract: "Art2Mus enables artwork-to-music generation through visual conditioning..."
  }
];
```

## Step 2: Apply Appropriate SOP Template
```javascript
// For each paper, select SOP based on topic
function getPaperSummary(paper) {
  const { title, authors, abstract, topic } = paper;
  
  // Load appropriate SOP template
  const sopTemplate = getSOPForTopic(topic);
  
  // Fill template variables
  const userPrompt = sopTemplate
    .replace('{{title}}', title)
    .replace('{{authors}}', authors)
    .replace('{{abstract}}', abstract)
    .replace('{{topic}}', topic);
    
  // Combine with system prompt
  const fullPrompt = SYSTEM_PROMPT + '\n\n' + userPrompt;
  
  return fullPrompt;
}
```

## Step 3: Generate and Save Summary
```javascript
// Generate summary using AI model
const summary = generateSummary(fullPrompt);

// Save to organized directory structure
const safeTitle = title.replace(/[^a-zA-Z0-9]/g, '_');
const filePath = `research/music_generation/ai_summaries/${safeTitle}.md`;
writeFile(filePath, summary);

// Save prompt for reproducibility
const promptPath = `research/music_generation/prompts/${safeTitle}_prompt.txt`;
writeFile(promptPath, fullPrompt);
```

## Expected Output Structure

### File: `SongEcho_Towards_Cover_Song_Generation_via_Instance-Adaptive_Element-wise_Linear_Modulation.md`
```markdown
# SongEcho: Towards Cover Song Generation via Instance-Adaptive Element-wise Linear Modulation

## 论文信息
- **标题**: SongEcho: Towards Cover Song Generation via Instance-Adaptive Element-wise Linear Modulation
- **作者**: Sifei Li, Yang Li, Zizhou Wang, Yuxin Zhang, Fuzhang Wu, Oliver Deussen, Tong-Yee Lee, Weiming Dong
- **机构**: 中国科学院自动化研究所、中国科学院大学人工智能学院、中国科学院软件研究所、康斯坦茨大学、国立成功大学
- **发表**: ICLR 2026
- **arXiv**: [2602.19976](https://arxiv.org/abs/2602.19976)
- **代码**: [GitHub](https://github.com/lsfhuihuiff/SongEcho_ICLR2026)

## 核心贡献
[1500+字的方法论深度剖析]
[1000+字的实验评估]
[完整的分析正文，总计3000+字]
```

## Quality Assurance Checklist

- [ ] Methodology critique ≥ 1500 characters
- [ ] Experimental assessment ≥ 1000 characters  
- [ ] Total analysis ≥ 3000 characters
- [ ] Critical perspective (not just restatement)
- [ ] Technical precision with correct terminology
- [ ] Proper file naming and directory structure
- [ ] Prompt saved for reproducibility