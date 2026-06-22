function workflowChaptersMixin() {
    return {
        inlinePreviewChapter: 0,
        inlinePreviewContent: '',
        inlinePreviewEditing: false,
        inlinePreviewEditContent: '',
        viewingChapterNum: 0,
        viewingChapterTitle: '',

        generateChapter(num) {
            this.generating = true;
            this.generatedContent = '';
            this.viewingChapterNum = num;
            this.viewingChapterTitle = '';
            fetch(`/projects/${this.projectId}/bg-gen/start?step=CHAPTER_WRITING&chapter=${num}`, {method:'POST'})
                .then(r => r.json())
                .then(data => {
                    if (data.status === 'ok') {
                        this.startElapsedTimer();
                        this.attachSimpleStream('CHAPTER_WRITING', num, () => this.loadChapterList());
                    } else {
                        alert('启动失败: ' + data.message);
                        this.generating = false;
                    }
                })
                .catch(err => { alert('启动失败: ' + err); this.generating = false; });
        },

        generateNextChapter() {
            const nextNum = this.chapterListData.length + 1;
            if (nextNum > this.totalChapters) {
                alert('已达到设定的最大章节数: ' + this.totalChapters);
                return;
            }
            this.generateChapterWithAutoSave(nextNum);
        },

        generateAllChapters() {
            let startNum = this.totalChapters + 1;
            for (let i = 0; i < this.chapterListData.length; i++) {
                const ch = this.chapterListData[i];
                if (ch.wordCount === 0) {
                    startNum = ch.chapterNumber;
                    break;
                }
            }
            if (startNum > this.totalChapters && this.chapterListData.length < this.totalChapters) {
                startNum = this.chapterListData.length + 1;
            }
            if (startNum > this.totalChapters) {
                alert('所有章节已生成完毕！');
                return;
            }
            this.autoGenerating = true;
            this.autoGenerateFrom(startNum);
        },

        autoGenerateFrom(chapterNum) {
            if (chapterNum > this.totalChapters || !this.autoGenerating) {
                this.autoGenerating = false;
                this.autoProgress = '';
                if (chapterNum > this.totalChapters) {
                    alert('全部章节自动生成完成！共 ' + this.totalChapters + ' 章');
                }
                this.loadChapterList();
                return;
            }
            this.autoProgress = `正在自动生成第 ${chapterNum}/${this.totalChapters} 章...`;
            this.generateChapterWithAutoSave(chapterNum, () => {
                this.loadChapterList();
                if (this.autoGenerating) {
                    this.autoGenerateFrom(chapterNum + 1);
                }
            });
        },

        generateChapterWithAutoSave(num, onComplete) {
            this.generating = true;
            this.generatedContent = '';
            this.viewingChapterNum = num;
            this.viewingChapterTitle = '';
            fetch(`/projects/${this.projectId}/bg-gen/start?step=CHAPTER_WRITING&chapter=${num}`, {method:'POST'})
                .then(r => r.json())
                .then(data => {
                    if (data.status === 'ok') {
                        this.startElapsedTimer();
                        this.attachSimpleStream('CHAPTER_WRITING', num, onComplete);
                    } else {
                        alert('启动失败: ' + data.message);
                        this.generating = false;
                        this.autoGenerating = false;
                    }
                })
                .catch(err => { alert('启动失败: ' + err); this.generating = false; this.autoGenerating = false; });
        },

        generateAllTitles() {
            if (!confirm('将为所有章节自动生成标题，确认继续？')) return;
            this.generating = true;
            this.autoProgress = '正在生成章节标题...';
            const url = `/projects/${this.projectId}/chapters/generate-titles`;
            const eventSource = new EventSource(url);
            this.currentEventSource = eventSource;
            eventSource.addEventListener('title', (e) => {
                const [num, ...titleParts] = e.data.split('|');
                const title = titleParts.join('|');
                this.autoProgress = `已生成第 ${num} 章标题: ${title}`;
                const ch = this.chapterListData.find(c => c.chapterNumber == num);
                if (ch) ch.title = title;
            });
            eventSource.addEventListener('done', () => {
                this.generating = false;
                this.autoProgress = '';
                this.currentEventSource = null;
                eventSource.close();
                this.loadChapterList();
            });
            eventSource.addEventListener('error', (e) => {
                this.generating = false;
                this.autoProgress = '';
                this.currentEventSource = null;
                if (e.data) alert('生成标题出错: ' + e.data);
                eventSource.close();
            });
            eventSource.onerror = () => {
                this.generating = false;
                this.autoProgress = '';
                this.currentEventSource = null;
                eventSource.close();
            };
        },

        previewChapter(num) {
            fetch(`/projects/${this.projectId}/chapters/${num}/content`)
                .then(r => {
                    if (!r.ok) throw new Error('请求失败');
                    return r.json();
                })
                .then(data => {
                    if (this.currentStep === 'CHAPTER_WRITING') {
                        this.inlinePreviewChapter = num;
                        this.inlinePreviewContent = data.content || '（暂无内容）';
                        this.inlinePreviewEditing = false;
                        this.inlinePreviewEditContent = '';
                    } else if (this.currentStep === 'POLISHING') {
                        if (this.polishPreviews[num]) {
                            const { [num]: _, ...rest } = this.polishPreviews;
                            this.polishPreviews = rest;
                        } else {
                            this.polishPreviews = { ...this.polishPreviews, [num]: {
                                draft: data.contentDraft || '',
                                content: data.content || '（暂无内容）'
                            }};
                        }
                    } else {
                        this.editableContent = data.content || '（暂无内容）';
                        this.existingContent = data.content || '';
                        this.generatedContent = '';
                        this.editing = false;
                        this.viewingChapterNum = data.chapterNumber;
                        this.viewingChapterTitle = data.title || '';
                        this.viewingChapterDraft = data.contentDraft || '';
                    }
                })
                .catch(err => alert('加载章节内容失败'));
        },

        closeInlinePreview() {
            this.inlinePreviewChapter = 0;
            this.inlinePreviewContent = '';
            this.inlinePreviewEditing = false;
        },

        startInlineEdit() {
            this.inlinePreviewEditing = true;
            this.inlinePreviewEditContent = this.inlinePreviewContent;
        },

        saveInlineEdit() {
            const num = this.inlinePreviewChapter;
            const content = this.inlinePreviewEditContent;
            const formData = new FormData();
            formData.append('content', content);
            fetch(`/projects/${this.projectId}/chapters/${num}/save-ajax`, {
                method: 'POST',
                body: formData
            })
            .then(r => r.json())
            .then(data => {
                if (data.status === 'ok') {
                    this.inlinePreviewContent = content;
                    this.inlinePreviewEditing = false;
                    this.loadChapterList();
                }
            })
            .catch(err => alert('保存失败: ' + err));
        }
    };
}
