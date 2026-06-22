function workflowProofreadingMixin() {
    return {
        proofreadData: [],
        proofreadStreaming: null,
        proofreadLoaded: false,
        proofreadExpandedChapter: null,
        proofreadFixGenerating: false,
        proofreadFixChapterNum: 0,
        proofreadFixDraft: '',
        proofreadFixContent: '',
        proofreadFixPreviews: {},
        replacePairs: [{oldText:'',newText:''},{oldText:'',newText:''},{oldText:'',newText:''},{oldText:'',newText:''},{oldText:'',newText:''}],
        globalReplaceLog: [],
        globalReplacing: false,

        setupProofreadStreamEvents(eventSource) {
            eventSource.addEventListener('proofread-section', (e) => {
                const match = e.data.match(/\[\[PROOFREAD:CHAPTER:(\d+):(\w+)\]\]/);
                if (match) {
                    this.proofreadStreaming = {
                        chapterNumber: parseInt(match[1]),
                        substep: match[2]
                    };
                    this.proofreadFixGenerating = false;
                }
            });

            eventSource.addEventListener('proofread-fix-section', (e) => {
                const match = e.data.match(/\[\[PROOFREAD_FIX:CHAPTER:(\d+)\]\]/);
                if (match) {
                    const chNum = parseInt(match[1]);
                    this.proofreadStreaming = {
                        chapterNumber: chNum,
                        substep: 'FIX'
                    };
                    this.proofreadFixGenerating = true;
                    this.proofreadFixChapterNum = chNum;
                    this.proofreadFixContent = '';
                }
            });

            eventSource.addEventListener('token', (e) => {
                this.generatedContent += e.data;
                if (this.proofreadFixGenerating) {
                    this.proofreadFixContent += e.data;
                }
            });

            eventSource.addEventListener('done', () => {
                this.generating = false;
                this.proofreadStreaming = null;
                this.proofreadFixGenerating = false;
                this.currentEventSource = null;
                this.stopElapsedTimer();
                eventSource.close();
                this.loadProofreadData();
            });

            eventSource.addEventListener('stopped', () => {
                this.generating = false;
                this.proofreadStreaming = null;
                this.proofreadFixGenerating = false;
                this.currentEventSource = null;
                this.stopElapsedTimer();
                eventSource.close();
                this.loadProofreadData();
            });

            eventSource.addEventListener('error', (e) => {
                this.generating = false;
                this.proofreadStreaming = null;
                this.currentEventSource = null;
                this.stopElapsedTimer();
                if (e.data) alert('校对出错: ' + e.data);
                eventSource.close();
            });

            eventSource.onerror = () => {
                this.currentEventSource = null;
                eventSource.close();
            };
        },

        loadProofreadData() {
            fetch(`/projects/${this.projectId}/proofreading/data`)
                .then(r => r.json())
                .then(data => {
                    this.proofreadData = data;
                    this.proofreadLoaded = true;
                })
                .catch(err => console.error('Failed to load proofreading data:', err));
        },

        getSubstepLabel(substep) {
            const labels = {
                'PLOT_SUMMARY': '情节提取',
                'CHARACTER_CHECK': '角色校正',
                'CONSISTENCY': '一致性检查',
                'CONTINUITY': '衔接检查',
                'FORESHADOWING': '伏笔追踪',
                'FIX': '精修正文'
            };
            return labels[substep] || substep;
        },

        parseJson(str) {
            if (!str) return [];
            try {
                let cleaned = str.trim();
                if (cleaned.startsWith('```')) {
                    cleaned = cleaned.replace(/^```(?:json)?\s*/, '').replace(/\s*```$/, '');
                }
                const parsed = JSON.parse(cleaned);
                return Array.isArray(parsed) ? parsed : [];
            } catch (e) { return []; }
        },

        proofreadCompletedCount() {
            return this.proofreadData.filter(d => d.proofreadStatus === 'GENERATED' || d.proofreadStatus === 'CONFIRMED').length;
        },

        proofreadFixChapter(num) {
            this.proofreadFixGenerating = true;
            this.proofreadFixChapterNum = num;
            this.proofreadFixContent = '';
            const { [num]: _, ...restFix } = this.proofreadFixPreviews;
            this.proofreadFixPreviews = restFix;
            const item = this.proofreadData.find(d => d.chapterNumber === num);
            if (item) item.proofreadFixStatus = 'GENERATING';
            fetch(`/projects/${this.projectId}/chapters/${num}/content`)
                .then(r => r.json())
                .then(data => {
                    this.proofreadFixDraft = data.content || '';
                });
            const eventSource = new EventSource(`/projects/${this.projectId}/proofread-fix/${num}`);
            eventSource.addEventListener('token', (e) => {
                this.proofreadFixContent += e.data;
            });
            eventSource.addEventListener('done', () => {
                this.proofreadFixGenerating = false;
                eventSource.close();
                this.loadProofreadData();
                this.loadChapterList();
            });
            eventSource.addEventListener('error', (e) => {
                this.proofreadFixGenerating = false;
                eventSource.close();
                if (e.data) alert('精修出错: ' + e.data);
            });
            eventSource.onerror = () => {
                this.proofreadFixGenerating = false;
                eventSource.close();
            };
        },

        previewProofreadFix(num) {
            if (this.proofreadFixPreviews[num]) {
                const { [num]: _, ...rest } = this.proofreadFixPreviews;
                this.proofreadFixPreviews = rest;
                return;
            }
            fetch(`/projects/${this.projectId}/chapters/${num}/content`)
                .then(r => r.json())
                .then(data => {
                    this.proofreadFixPreviews = { ...this.proofreadFixPreviews, [num]: {
                        draft: data.contentBeforeFix || '',
                        content: data.content || ''
                    }};
                });
        },

        openGlobalReplace() {
            this.globalReplaceLog = [];
            this.globalReplacing = false;
            new bootstrap.Modal(document.getElementById('globalReplaceModal')).show();
        },

        executeGlobalReplace() {
            const validPairs = this.replacePairs.filter(p => p.oldText.trim() !== '');
            if (validPairs.length === 0) {
                alert('请至少输入一对替换文本');
                return;
            }
            this.globalReplacing = true;
            this.globalReplaceLog = ['开始执行全局替换...'];

            const params = new URLSearchParams();
            validPairs.forEach(p => {
                params.append('oldTexts', p.oldText);
                params.append('newTexts', p.newText);
            });

            const es = new EventSource(`/projects/${this.projectId}/global-replace?${params.toString()}`);
            es.addEventListener('progress', (e) => {
                this.globalReplaceLog.push(e.data);
            });
            es.addEventListener('done', (e) => {
                this.globalReplaceLog.push(e.data);
                this.globalReplacing = false;
                es.close();
                this.loadChapterList();
                this.loadCharacterList();
                if (this.currentStep === 'PROOFREADING') this.loadProofreadData();
            });
            es.addEventListener('error', (e) => {
                if (e.data) this.globalReplaceLog.push('错误: ' + e.data);
                this.globalReplacing = false;
                es.close();
            });
            es.onerror = () => {
                if (this.globalReplacing) {
                    this.globalReplaceLog.push('连接断开');
                    this.globalReplacing = false;
                }
                es.close();
            };
        }
    };
}
