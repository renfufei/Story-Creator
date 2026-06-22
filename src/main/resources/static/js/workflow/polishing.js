function workflowPolishingMixin() {
    return {
        viewingChapterDraft: '',
        polishPreviews: {},
        polishEditingChapter: null,
        polishEditContent: '',
        polishNoteChapter: 0,
        polishNoteText: '',
        polishNoteModal: null,
        batchPolishList: [],
        batchPolishIndex: 0,

        polishChapter(num) {
            this.generating = true;
            this.generatedContent = '';
            this.viewingChapterNum = num;
            this.viewingChapterTitle = '';
            const { [num]: _, ...restPrev } = this.polishPreviews;
            this.polishPreviews = restPrev;
            fetch(`/projects/${this.projectId}/chapters/${num}/content`)
                .then(r => r.json())
                .then(data => {
                    this.viewingChapterDraft = data.content || '';
                });
            fetch(`/projects/${this.projectId}/bg-gen/start?step=POLISHING&chapter=${num}`, {method:'POST'})
                .then(r => r.json())
                .then(data => {
                    if (data.status === 'ok') {
                        this.startElapsedTimer();
                        this.attachSimpleStream('POLISHING', num, () => {
                            if (this.viewingChapterDraft && this.generatedContent) {
                                this.polishPreviews = { ...this.polishPreviews, [num]: {
                                    draft: this.viewingChapterDraft,
                                    content: this.generatedContent
                                }};
                            }
                            this.viewingChapterDraft = '';
                            this.loadChapterList();
                        });
                    } else {
                        alert('启动失败: ' + data.message);
                        this.generating = false;
                    }
                })
                .catch(err => { alert('启动失败: ' + err); this.generating = false; });
        },

        batchPolish() {
            const needsPolish = this.chapterListData.filter(ch =>
                ch.polishStatus !== 'CONFIRMED'
            );
            if (needsPolish.length === 0) {
                alert('所有章节均已润色完毕！');
                return;
            }
            this.autoGenerating = true;
            this.batchPolishList = needsPolish.map(ch => ch.chapterNumber);
            this.batchPolishIndex = 0;
            this.batchPolishNext();
        },

        batchPolishNext() {
            if (this.batchPolishIndex >= this.batchPolishList.length || !this.autoGenerating) {
                this.autoGenerating = false;
                this.autoProgress = '';
                this.viewingChapterDraft = '';
                if (this.batchPolishIndex >= this.batchPolishList.length) {
                    alert('批量润色完成！');
                }
                this.loadChapterList();
                return;
            }
            const chNum = this.batchPolishList[this.batchPolishIndex];
            this.autoProgress = `正在润色第 ${chNum} 章（${this.batchPolishIndex + 1}/${this.batchPolishList.length}）...`;
            this.generating = true;
            this.generatedContent = '';
            this.viewingChapterNum = chNum;
            fetch(`/projects/${this.projectId}/chapters/${chNum}/content`)
                .then(r => r.json())
                .then(data => { this.viewingChapterDraft = data.content || ''; });
            fetch(`/projects/${this.projectId}/bg-gen/start?step=POLISHING&chapter=${chNum}`, {method:'POST'})
                .then(r => r.json())
                .then(data => {
                    if (data.status === 'ok') {
                        this.startElapsedTimer();
                        this.attachSimpleStream('POLISHING', chNum, () => {
                            if (this.viewingChapterDraft && this.generatedContent) {
                                this.polishPreviews = { ...this.polishPreviews, [chNum]: {
                                    draft: this.viewingChapterDraft,
                                    content: this.generatedContent
                                }};
                            }
                            this.viewingChapterDraft = '';
                            this.batchPolishIndex++;
                            this.loadChapterList();
                            if (this.autoGenerating) {
                                this.batchPolishNext();
                            }
                        });
                    } else {
                        alert('润色启动失败: ' + data.message);
                        this.generating = false;
                        this.autoGenerating = false;
                    }
                })
                .catch(err => { alert('润色启动失败: ' + err); this.generating = false; this.autoGenerating = false; });
        },

        savePolishEdit(num) {
            const content = this.polishEditContent;
            const formData = new FormData();
            formData.append('content', content);
            fetch(`/projects/${this.projectId}/chapters/${num}/save-ajax`, {
                method: 'POST',
                body: formData
            })
            .then(r => r.json())
            .then(data => {
                if (data.status === 'ok') {
                    if (this.polishPreviews[num]) {
                        this.polishPreviews = { ...this.polishPreviews, [num]: {
                            ...this.polishPreviews[num],
                            content: content
                        }};
                    }
                    this.polishEditingChapter = null;
                    this.loadChapterList();
                }
            })
            .catch(err => alert('保存失败: ' + err));
        },

        editPolishNote(chapterNum, currentNote) {
            this.polishNoteChapter = chapterNum;
            this.polishNoteText = currentNote || '';
            if (!this.polishNoteModal) {
                this.polishNoteModal = new bootstrap.Modal(document.getElementById('polishNoteModal'));
            }
            this.polishNoteModal.show();
        },

        savePolishNote() {
            const formData = new FormData();
            formData.append('note', this.polishNoteText);
            fetch(`/projects/${this.projectId}/chapters/${this.polishNoteChapter}/polish-note`, {
                method: 'POST',
                body: formData
            })
            .then(r => r.json())
            .then(() => {
                if (this.polishNoteModal) this.polishNoteModal.hide();
                this.loadChapterList();
            })
            .catch(err => alert('保存失败: ' + err));
        }
    };
}
