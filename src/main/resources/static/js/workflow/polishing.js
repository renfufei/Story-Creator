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

        /* ===== 预览（写作步看正文、润色步看「原稿 / 润色后」对照） =====
           写作步与润色步用的是同一个按钮：展开哪一份，由 currentStep 决定。 */
        previewOpen(num) {
            return this.currentStep === 'POLISHING'
                ? !!this.polishPreviews[num]
                : this.inlinePreviewChapter === num;
        },

        togglePreview(num) {
            if (this.currentStep === 'POLISHING') {
                this.previewChapter(num);        // 已展开则收起，否则现拉
                return;
            }
            if (this.inlinePreviewChapter === num) {
                this.closeInlinePreview();
                return;
            }
            this.previewChapter(num);
        },

        /** 把 {draft, content} 组装成面板要的数据：句级差异表 + 两侧字数 + 高亮开关 */
        _polishPreview(draft, content, highlight) {
            const d = draft || '';
            const c = content || '';
            const pair = SCCmp.preparePair(d, c, {
                highlight: highlight === undefined ? true : highlight
            });
            return Object.assign({ draft: d, content: c }, pair);
        },

        _setPolishPreview(num, draft, content, highlight) {
            const prev = this.polishPreviews[num];
            const hl = highlight === undefined
                ? (prev && prev.highlight !== undefined ? prev.highlight : true)
                : highlight;
            this.polishPreviews = {
                ...this.polishPreviews,
                [num]: this._polishPreview(draft, content, hl)
            };
        },

        /** 收起这一章的对照面板（只影响自己，其它章不受影响） */
        closePolishPreview(num) {
            const { [num]: _, ...rest } = this.polishPreviews;
            this.polishPreviews = rest;
            if (this.polishEditingChapter === num) this.polishEditingChapter = null;
        },

        beginPolishEdit(num) {
            this.polishEditingChapter = num;
            this.polishEditContent = (this.polishPreviews[num] || {}).content || '';
        },

        polishChapter(num) {
            this.generating = true;
            this.generatedContent = '';
            this.viewingChapterNum = num;
            this.viewingChapterTitle = '';
            const matParam = this.getMaterialIdsParam ? this.getMaterialIdsParam() : '';
            const matSuffix = matParam ? '&' + matParam : '';
            const { [num]: _, ...restPrev } = this.polishPreviews;
            this.polishPreviews = restPrev;
            fetch(`/projects/${this.projectId}/chapters/${num}/content`)
                .then(checkResponse)
                .then(data => {
                    this.viewingChapterDraft = data.content || '';
                });
            fetch(`/projects/${this.projectId}/bg-gen/start?step=POLISHING&chapter=${num}${matSuffix}`, {method:'POST'})
                .then(checkResponse)
                .then(data => {
                    if (data.status === 'ok') {
                        this.startElapsedTimer();
                        this.attachSimpleStream('POLISHING', num, () => {
                            if (this.viewingChapterDraft && this.generatedContent) {
                                this._setPolishPreview(num, this.viewingChapterDraft, this.generatedContent);
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
                .then(checkResponse)
                .then(data => { this.viewingChapterDraft = data.content || ''; });
            fetch(`/projects/${this.projectId}/bg-gen/start?step=POLISHING&chapter=${chNum}`, {method:'POST'})
                .then(checkResponse)
                .then(data => {
                    if (data.status === 'ok') {
                        this.startElapsedTimer();
                        this.attachSimpleStream('POLISHING', chNum, () => {
                            if (this.viewingChapterDraft && this.generatedContent) {
                                this._setPolishPreview(chNum, this.viewingChapterDraft, this.generatedContent);
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
            .then(checkResponse)
            .then(data => {
                if (data.status === 'ok') {
                    if (this.polishPreviews[num]) {
                        // 正文改了，差异表要跟着重算（原稿不变，只换后一版）
                        this._setPolishPreview(num, this.polishPreviews[num].draft, content);
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
            .then(checkResponse)
            .then(() => {
                if (this.polishNoteModal) this.polishNoteModal.hide();
                this.loadChapterList();
            })
            .catch(err => alert('保存失败: ' + err));
        }
    };
}
