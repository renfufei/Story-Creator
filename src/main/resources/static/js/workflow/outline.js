function workflowOutlineMixin() {
    return {
        outlineVolumes: [],
        outlineSummary: '',
        outlineSummaryEdit: '',
        editingSummary: false,
        streamingTarget: null,
        outlineLoaded: false,
        outlineStreamingStatus: '',
        _outlineMergeLoading: false,

        setupOutlineStreamEvents(eventSource) {
            eventSource.addEventListener('outline-section', (e) => {
                const marker = e.data;
                const match = marker.match(/\[\[SECTION:(\w+)(?::(.+))?\]\]/);
                if (!match) return;
                const type = match[1];
                const params = match[2] ? match[2].split(':') : [];

                if (this.streamingTarget && (this.streamingTarget.type === 'CHAPTER' || this.streamingTarget.type === 'REFINE')) {
                    const prevVol = this.outlineVolumes.find(v => v.volumeNumber === this.streamingTarget.volumeNumber);
                    if (prevVol) {
                        const prevCh = prevVol.chapters.find(c => c.chapterNumber === this.streamingTarget.chapterNumber);
                        if (prevCh && (prevCh.status === 'GENERATING' || prevCh.status === 'REFINING')) {
                            prevCh.status = this.streamingTarget.type === 'REFINE' ? 'REFINED' : 'COMPLETED';
                        }
                    }
                }

                if (type === 'VOLUME') {
                    const volNum = parseInt(params[0]);
                    const chStart = parseInt(params[1]);
                    const chEnd = parseInt(params[2]);
                    this.outlineStreamingStatus = `正在生成第${volNum}卷故事弧线...`;
                    let vol = this.outlineVolumes.find(v => v.volumeNumber === volNum);
                    if (!vol) {
                        vol = {
                            volumeNumber: volNum, title: '第' + volNum + '卷',
                            arcSummary: '', chapterStart: chStart, chapterEnd: chEnd,
                            expanded: true, editingArc: false, arcSummaryEdit: '', chapters: []
                        };
                        this.outlineVolumes.push(vol);
                    }
                    this.streamingTarget = { type: 'VOLUME', volumeNumber: volNum };
                } else if (type === 'CHAPTER') {
                    const chNum = parseInt(params[0]);
                    const volNum = parseInt(params[1]);
                    this.outlineStreamingStatus = `正在生成第${chNum}章大纲...`;
                    let vol = this.outlineVolumes.find(v => v.volumeNumber === volNum);
                    if (!vol) {
                        vol = {
                            volumeNumber: volNum, title: '第' + volNum + '卷',
                            arcSummary: '', chapterStart: chNum, chapterEnd: chNum,
                            expanded: true, editingArc: false, arcSummaryEdit: '', chapters: []
                        };
                        this.outlineVolumes.push(vol);
                        this.loadOutlineData(true);
                    } else {
                        const maxExisting = vol.chapters.length > 0
                            ? Math.max(...vol.chapters.map(c => c.chapterNumber))
                            : vol.chapterStart - 1;
                        if (chNum > maxExisting + 1) {
                            this.loadOutlineData(true);
                        }
                    }
                    let ch = vol.chapters.find(c => c.chapterNumber === chNum);
                    if (!ch) {
                        ch = { chapterNumber: chNum, title: '', summary: '', characterNames: '',
                               status: 'GENERATING', editing: false, titleEdit: '', summaryEdit: '', characterNamesEdit: '' };
                        vol.chapters.push(ch);
                    } else {
                        ch.status = 'GENERATING';
                    }
                    this.streamingTarget = { type: 'CHAPTER', chapterNumber: chNum, volumeNumber: volNum };
                } else if (type === 'REFINE') {
                    const chNum = parseInt(params[0]);
                    const volNum = parseInt(params[1]);
                    this.outlineStreamingStatus = `正在精修第${chNum}章大纲...`;
                    let vol = this.outlineVolumes.find(v => v.volumeNumber === volNum);
                    if (vol) {
                        let ch = vol.chapters.find(c => c.chapterNumber === chNum);
                        if (ch) {
                            ch.summary = '';
                            ch.title = '';
                            ch.characterNames = '';
                            ch.status = 'REFINING';
                        }
                    }
                    this.streamingTarget = { type: 'REFINE', chapterNumber: chNum, volumeNumber: volNum };
                } else if (type === 'SUMMARY') {
                    this.outlineStreamingStatus = '正在生成故事总纲...';
                    this.streamingTarget = { type: 'SUMMARY' };
                }
            });

            eventSource.addEventListener('token', (e) => {
                const token = e.data;
                if (this.streamingTarget) {
                    if (this.streamingTarget.type === 'VOLUME') {
                        const vol = this.outlineVolumes.find(v => v.volumeNumber === this.streamingTarget.volumeNumber);
                        if (vol) vol.arcSummary += token;
                    } else if (this.streamingTarget.type === 'CHAPTER' || this.streamingTarget.type === 'REFINE') {
                        const vol = this.outlineVolumes.find(v => v.volumeNumber === this.streamingTarget.volumeNumber);
                        if (vol) {
                            const ch = vol.chapters.find(c => c.chapterNumber === this.streamingTarget.chapterNumber);
                            if (ch) {
                                ch.summary += token;
                                if (!ch.title) {
                                    const titleMatch = ch.summary.match(/\*\*标题[：:]\*\*\s*(.+)/);
                                    if (titleMatch) ch.title = titleMatch[1].trim();
                                }
                                if (!ch.characterNames) {
                                    const charMatch = ch.summary.match(/\*\*出场角色[：:]\*\*\s*(.+)/);
                                    if (charMatch) ch.characterNames = charMatch[1].trim();
                                }
                            }
                        }
                    } else if (this.streamingTarget.type === 'SUMMARY') {
                        this.outlineSummary += token;
                    }
                }
            });

            eventSource.addEventListener('done', () => {
                this.generating = false;
                this.currentEventSource = null;
                this.streamingTarget = null;
                this.outlineStreamingStatus = '';
                this.outlineLoaded = true;
                this.stopElapsedTimer();
                eventSource.close();
                this.loadOutlineData();
            });

            eventSource.addEventListener('stopped', () => {
                this.generating = false;
                this.currentEventSource = null;
                this.streamingTarget = null;
                this.outlineStreamingStatus = '';
                this.stopElapsedTimer();
                eventSource.close();
                this.loadOutlineData();
            });

            eventSource.addEventListener('error', (e) => {
                this.generating = false;
                this.currentEventSource = null;
                this.streamingTarget = null;
                this.outlineStreamingStatus = '';
                this.stopElapsedTimer();
                if (e.data) alert('生成出错: ' + e.data);
                eventSource.close();
            });

            eventSource.onerror = () => {
                this.currentEventSource = null;
                eventSource.close();
            };
        },

        loadOutlineData(mergeOnly = false) {
            if (mergeOnly && this._outlineMergeLoading) return;
            if (mergeOnly) this._outlineMergeLoading = true;
            fetch(`/projects/${this.projectId}/outline/data`)
                .then(r => r.json())
                .then(data => {
                    if (mergeOnly) this._outlineMergeLoading = false;
                    if (mergeOnly && this.generating) {
                        this.outlineSummary = data.storySummary || this.outlineSummary;
                        if (data.volumes && data.volumes.length > 0) {
                            for (const sv of data.volumes) {
                                let vol = this.outlineVolumes.find(v => v.volumeNumber === sv.volumeNumber);
                                if (!vol) {
                                    vol = {
                                        volumeNumber: sv.volumeNumber, title: sv.title,
                                        arcSummary: sv.arcSummary || '', chapterStart: sv.chapterStart,
                                        chapterEnd: sv.chapterEnd, expanded: true,
                                        editingArc: false, arcSummaryEdit: '', chapters: []
                                    };
                                    this.outlineVolumes.push(vol);
                                } else if (!vol.arcSummary && sv.arcSummary) {
                                    vol.arcSummary = sv.arcSummary;
                                }
                                for (const sch of (sv.chapters || [])) {
                                    let existingCh = vol.chapters.find(c => c.chapterNumber === sch.chapterNumber);
                                    if (!existingCh) {
                                        vol.chapters.push({
                                            chapterNumber: sch.chapterNumber,
                                            title: sch.title || '', summary: sch.summary || '',
                                            characterNames: sch.characterNames || '',
                                            status: sch.status || 'COMPLETED',
                                            editing: false, regenerating: false,
                                            titleEdit: '', summaryEdit: '', characterNamesEdit: ''
                                        });
                                    } else if (sch.status) {
                                        existingCh.status = sch.status;
                                    }
                                }
                                vol.chapters.sort((a, b) => a.chapterNumber - b.chapterNumber);
                            }
                            this.outlineVolumes.sort((a, b) => a.volumeNumber - b.volumeNumber);
                        }
                        return;
                    }
                    this.outlineSummary = data.storySummary || '';
                    if (data.volumes && data.volumes.length > 0) {
                        this.outlineVolumes = data.volumes.map(v => ({
                            volumeNumber: v.volumeNumber,
                            title: v.title,
                            arcSummary: v.arcSummary || '',
                            chapterStart: v.chapterStart,
                            chapterEnd: v.chapterEnd,
                            expanded: true,
                            editingArc: false,
                            arcSummaryEdit: '',
                            chapters: (v.chapters || []).map(ch => ({
                                chapterNumber: ch.chapterNumber,
                                title: ch.title || '',
                                summary: ch.summary || '',
                                characterNames: ch.characterNames || '',
                                status: ch.status || 'COMPLETED',
                                editing: false,
                                regenerating: false,
                                titleEdit: '',
                                summaryEdit: '',
                                characterNamesEdit: ''
                            }))
                        }));
                        this.outlineLoaded = true;
                    } else if (data.legacyChapters && data.legacyChapters.length > 0) {
                        this.outlineVolumes = [{
                            volumeNumber: 1,
                            title: '全部章节',
                            arcSummary: '',
                            chapterStart: 1,
                            chapterEnd: data.legacyChapters.length,
                            expanded: true,
                            editingArc: false,
                            arcSummaryEdit: '',
                            chapters: data.legacyChapters.map(ch => ({
                                chapterNumber: ch.chapterNumber,
                                title: ch.title || '',
                                summary: ch.summary || '',
                                characterNames: ch.characterNames || '',
                                status: ch.status || 'COMPLETED',
                                editing: false,
                                regenerating: false,
                                titleEdit: '',
                                summaryEdit: '',
                                characterNamesEdit: ''
                            }))
                        }];
                        this.outlineLoaded = true;
                    }
                })
                .catch(err => {
                    this._outlineMergeLoading = false;
                    console.error('Failed to load outline data:', err);
                });
        },

        editOutlineSummary() {
            this.editingSummary = true;
            this.outlineSummaryEdit = this.outlineSummary;
        },

        saveOutlineSummary() {
            const formData = new FormData();
            formData.append('content', this.outlineSummaryEdit);
            fetch(`/projects/${this.projectId}/outline/edit-summary`, { method: 'POST', body: formData })
                .then(r => r.json())
                .then(() => {
                    this.outlineSummary = this.outlineSummaryEdit;
                    this.editingSummary = false;
                })
                .catch(err => alert('保存失败: ' + err));
        },

        editVolumeArc(vol) {
            vol.editingArc = true;
            vol.arcSummaryEdit = vol.arcSummary;
        },

        saveVolumeArc(vol) {
            const formData = new FormData();
            formData.append('volumeNumber', vol.volumeNumber);
            formData.append('arcSummary', vol.arcSummaryEdit);
            fetch(`/projects/${this.projectId}/outline/edit-volume`, { method: 'POST', body: formData })
                .then(r => r.json())
                .then(() => {
                    vol.arcSummary = vol.arcSummaryEdit;
                    vol.editingArc = false;
                })
                .catch(err => alert('保存失败: ' + err));
        },

        editChapterOutline(ch) {
            ch.editing = true;
            ch.titleEdit = ch.title;
            ch.summaryEdit = ch.summary;
            ch.characterNamesEdit = ch.characterNames;
        },

        saveChapterOutline(ch) {
            const formData = new FormData();
            formData.append('chapterNumber', ch.chapterNumber);
            formData.append('title', ch.titleEdit);
            formData.append('summary', ch.summaryEdit);
            formData.append('characterNames', ch.characterNamesEdit);
            fetch(`/projects/${this.projectId}/outline/edit-chapter`, { method: 'POST', body: formData })
                .then(r => r.json())
                .then(() => {
                    ch.title = ch.titleEdit;
                    ch.summary = ch.summaryEdit;
                    ch.characterNames = ch.characterNamesEdit;
                    ch.editing = false;
                })
                .catch(err => alert('保存失败: ' + err));
        },

        regenerateChapterOutline(ch) {
            if (ch.regenerating) return;
            ch.regenerating = true;
            ch.status = 'GENERATING';
            ch.summary = '';
            ch.title = '';
            ch.characterNames = '';

            const url = `/projects/${this.projectId}/outline/regenerate-chapter?chapterNumber=${ch.chapterNumber}`;
            const eventSource = new EventSource(url);
            let content = '';

            eventSource.addEventListener('token', (e) => {
                content += e.data;
                const titleMatch = content.match(/\*\*标题[：:]\*\*\s*(.+)/);
                if (titleMatch) ch.title = titleMatch[1].trim();
                const charMatch = content.match(/\*\*出场角色[：:]\*\*\s*(.+)/);
                if (charMatch) ch.characterNames = charMatch[1].trim();
                ch.summary = content
                    .replace(/\*\*标题[：:]\*\*[^\n]*\n?/, '')
                    .replace(/\*\*出场角色[：:]\*\*[^\n]*\n?/, '')
                    .trim();
            });

            eventSource.addEventListener('done', () => {
                eventSource.close();
                ch.regenerating = false;
                ch.status = 'COMPLETED';
                this.loadOutlineData();
            });

            eventSource.addEventListener('error', (e) => {
                eventSource.close();
                ch.regenerating = false;
                ch.status = 'FAILED';
                alert('重新生成失败: ' + (e.data || '未知错误'));
            });

            eventSource.onerror = () => {
                eventSource.close();
                ch.regenerating = false;
                if (!content) {
                    ch.status = 'FAILED';
                }
            };
        }
    };
}
