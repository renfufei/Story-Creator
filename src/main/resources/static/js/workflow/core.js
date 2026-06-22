function workflowCoreMixin() {
    const d = window.__WORKFLOW_DATA__;
    return {
        generating: false,
        generatedContent: '',
        existingContent: d.currentContent || '',
        editableContent: d.currentContent || '',
        projectId: d.projectId,
        currentStep: d.currentStep,
        currentStepOrder: d.currentStepOrder,
        stepConfirmed: d.stepConfirmed,
        isLastStep: d.isLastStep,
        totalChapters: d.totalChapters,
        chapterWordCount: d.chapterWordCount,
        chapterWordCountMin: d.chapterWordCountMin,
        chapterWordCountMax: d.chapterWordCountMax,
        autoGenerating: false,
        autoProgress: '',
        currentEventSource: null,
        generateStartTime: null,
        generateElapsed: '',
        generateTimer: null,
        chapterListData: [],
        editing: false,
        stepGuidance: d.stepGuidance || '',
        stepGuidanceEdit: '',
        editingGuidance: false,
        stepList: [
            { name: 'WORLD_BUILDING', label: '世界观设定', order: 1 },
            { name: 'CHARACTER_DESIGN', label: '角色设计', order: 2 },
            { name: 'OUTLINE_GENERATION', label: '大纲生成', order: 3 },
            { name: 'CHAPTER_WRITING', label: '分章节写作', order: 4 },
            { name: 'POLISHING', label: '润色修改', order: 5 },
            { name: 'PROOFREADING', label: '校对精修', order: 6 },
        ],

        init() {
            // Initialize data from server-rendered values
            this.wfStateCurrentStep = d.projectCurrentStep;
            this.autoMode = d.autoMode;
            this.autoRunStepConfigs = d.autoRunStepConfigs;
            this.autoRunActiveStep = d.currentStep;
            this.autoRunActiveOrder = d.currentStepOrder;
            this.characterStateDims = d.characterStateDims;

            this.loadChapterList();
            this.loadCharacterList();
            if (this.currentStep === 'OUTLINE_GENERATION') {
                this.loadOutlineData();
            }
            if (this.currentStep === 'PROOFREADING') {
                this.loadProofreadData();
            }
            this.checkBgGenStatus();
            if (this.autoMode) {
                this.checkAutoRunStatus();
            }
            this.initGlobalScrollSync();
        },

        checkBgGenStatus() {
            if (['OUTLINE_GENERATION','CHARACTER_DESIGN','PROOFREADING'].includes(this.currentStep)) {
                fetch(`/projects/${this.projectId}/bg-gen/status?step=${this.currentStep}&chapter=0`)
                    .then(r => r.json())
                    .then(data => {
                        if (data.bgActive) {
                            this.generating = true;
                            this.startElapsedTimer();
                            if (this.currentStep === 'OUTLINE_GENERATION') {
                                this.outlineStreamingStatus = '后台正在生成大纲...';
                                this.loadOutlineData();
                            } else if (this.currentStep === 'CHARACTER_DESIGN') {
                                this.loadCharacterList();
                            } else if (this.currentStep === 'PROOFREADING') {
                                this.loadProofreadData();
                            }
                            this.attachBgStream(this.currentStep, 0);
                        }
                    })
                    .catch(() => {});
            } else if (['CHAPTER_WRITING','POLISHING'].includes(this.currentStep)) {
                fetch(`/projects/${this.projectId}/bg-gen/active-chapter?step=${this.currentStep}`)
                    .then(r => r.json())
                    .then(data => {
                        if (data.bgActive) {
                            this.generating = true;
                            this.viewingChapterNum = data.chapter;
                            this.startElapsedTimer();
                            this.attachSimpleStream(this.currentStep, data.chapter);
                        }
                    })
                    .catch(() => {});
            } else if (this.currentStep === 'WORLD_BUILDING') {
                fetch(`/projects/${this.projectId}/bg-gen/status?step=WORLD_BUILDING&chapter=0`)
                    .then(r => r.json())
                    .then(data => {
                        if (data.bgActive) {
                            this.generating = true;
                            this.startElapsedTimer();
                            this.attachSimpleStream('WORLD_BUILDING', 0);
                        }
                    })
                    .catch(() => {});
            }
        },

        attachBgStream(step, chapter) {
            if (this.currentEventSource) {
                this.currentEventSource.close();
            }
            const url = `/projects/${this.projectId}/bg-gen/stream?step=${step}&chapter=${chapter}`;
            const eventSource = new EventSource(url);
            this.currentEventSource = eventSource;

            eventSource.addEventListener('replay-buffer', (e) => {
                // For outline/character/proofreading, the replay-buffer contains raw tokens
            });

            if (step === 'OUTLINE_GENERATION') {
                this.setupOutlineStreamEvents(eventSource);
            } else if (step === 'CHARACTER_DESIGN') {
                this.setupCharacterStreamEvents(eventSource);
            } else if (step === 'PROOFREADING') {
                this.setupProofreadStreamEvents(eventSource);
            }
        },

        attachSimpleStream(step, chapter, onComplete) {
            if (this.currentEventSource) {
                this.currentEventSource.close();
            }
            const url = `/projects/${this.projectId}/bg-gen/stream?step=${step}&chapter=${chapter}`;
            const eventSource = new EventSource(url);
            this.currentEventSource = eventSource;

            eventSource.addEventListener('replay-buffer', (e) => {
                this.generatedContent = e.data;
                if (this.$refs.output) {
                    this.$nextTick(() => { this.$refs.output.scrollTop = this.$refs.output.scrollHeight; });
                }
            });

            eventSource.addEventListener('token', (e) => {
                this.generatedContent += e.data;
                if (this.$refs.output) {
                    this.$refs.output.scrollTop = this.$refs.output.scrollHeight;
                }
            });

            eventSource.addEventListener('done', () => {
                this.generating = false;
                this.currentEventSource = null;
                this.editableContent = this.generatedContent;
                this.editing = false;
                this.stopElapsedTimer();
                eventSource.close();
                this.loadChapterList();
                if (onComplete) onComplete();
            });

            eventSource.addEventListener('stopped', () => {
                this.generating = false;
                this.currentEventSource = null;
                this.stopElapsedTimer();
                eventSource.close();
            });

            eventSource.addEventListener('error', (e) => {
                this.generating = false;
                this.autoGenerating = false;
                this.autoProgress = '';
                this.currentEventSource = null;
                this.stopElapsedTimer();
                if (e.data) alert('生成出错: ' + e.data);
                eventSource.close();
            });

            eventSource.onerror = () => {
                this.currentEventSource = null;
                eventSource.close();
            };
        },

        generate() {
            this.generating = true;
            this.generatedContent = '';
            this.viewingChapterNum = 0;
            this.viewingChapterTitle = '';
            if (['OUTLINE_GENERATION','CHARACTER_DESIGN','PROOFREADING'].includes(this.currentStep)) {
                if (this.currentStep === 'OUTLINE_GENERATION') {
                    this.outlineVolumes = [];
                    this.outlineSummary = '';
                    this.streamingTarget = null;
                    this.outlineLoaded = false;
                    this.outlineStreamingStatus = '正在生成分卷大纲...';
                } else if (this.currentStep === 'PROOFREADING') {
                    this.proofreadStreaming = { chapterNumber: 0, substep: '' };
                }
                fetch(`/projects/${this.projectId}/bg-gen/start?step=${this.currentStep}&chapter=0`, {method:'POST'})
                    .then(r => r.json())
                    .then(data => {
                        if (data.status === 'ok') {
                            this.startElapsedTimer();
                            this.attachBgStream(this.currentStep, 0);
                        } else {
                            alert('启动失败: ' + data.message);
                            this.generating = false;
                        }
                    })
                    .catch(err => {
                        alert('启动失败: ' + err);
                        this.generating = false;
                    });
            } else {
                fetch(`/projects/${this.projectId}/bg-gen/start?step=${this.currentStep}&chapter=0`, {method:'POST'})
                    .then(r => r.json())
                    .then(data => {
                        if (data.status === 'ok') {
                            this.startElapsedTimer();
                            this.attachSimpleStream(this.currentStep, 0);
                        } else {
                            alert('启动失败: ' + data.message);
                            this.generating = false;
                        }
                    })
                    .catch(err => {
                        alert('启动失败: ' + err);
                        this.generating = false;
                    });
            }
        },

        startElapsedTimer() {
            this.generateStartTime = Date.now();
            this.generateElapsed = '0:00';
            if (this.generateTimer) clearInterval(this.generateTimer);
            this.generateTimer = setInterval(() => {
                const seconds = Math.floor((Date.now() - this.generateStartTime) / 1000);
                const min = Math.floor(seconds / 60);
                const sec = seconds % 60;
                this.generateElapsed = min + ':' + (sec < 10 ? '0' : '') + sec;
            }, 1000);
        },

        stopElapsedTimer() {
            if (this.generateTimer) {
                clearInterval(this.generateTimer);
                this.generateTimer = null;
            }
            if (this.generateStartTime) {
                const seconds = Math.floor((Date.now() - this.generateStartTime) / 1000);
                const min = Math.floor(seconds / 60);
                const sec = seconds % 60;
                this.generateElapsed = min + ':' + (sec < 10 ? '0' : '') + sec;
            }
        },

        stopAutoGenerate() {
            this.autoGenerating = false;
            this.autoProgress = '';
            this.generating = false;
            this.stopElapsedTimer();
            if (this.currentEventSource) {
                this.currentEventSource.close();
                this.currentEventSource = null;
            }
            const chapter = this.viewingChapterNum || 0;
            fetch(`/projects/${this.projectId}/bg-gen/stop?step=${this.currentStep}&chapter=${chapter}`, {method:'POST'});
        },

        loadChapterList() {
            fetch(`/projects/${this.projectId}/chapters/list`)
                .then(r => r.json())
                .then(data => { this.chapterListData = data; })
                .catch(err => console.error('Failed to load chapters:', err));
        },

        saveGuidance() {
            const formData = new FormData();
            formData.append('step', this.currentStep);
            formData.append('guidance', this.stepGuidanceEdit);
            fetch(`/projects/${this.projectId}/step-guidance`, {
                method: 'POST',
                body: formData
            })
            .then(r => r.json())
            .then(() => {
                this.stepGuidance = this.stepGuidanceEdit;
                this.editingGuidance = false;
            })
            .catch(err => alert('保存失败: ' + err));
        },

        confirmStep() {
            fetch(`/projects/${this.projectId}/confirm-only-ajax?step=${this.currentStep}`, {method:'POST'})
                .then(r => r.json())
                .then(data => {
                    if (data.status === 'ok') {
                        this.stepConfirmed = true;
                    }
                });
        },

        advanceStep() {
            fetch(`/projects/${this.projectId}/advance-ajax?step=${this.currentStep}`, {method:'POST'})
                .then(r => r.json())
                .then(data => {
                    if (data.status === 'ok') {
                        location.reload();
                    }
                });
        },

        initGlobalScrollSync() {
            let syncing = false;
            document.addEventListener('scroll', (e) => {
                if (syncing) return;
                const source = e.target;
                if (!source.classList || !source.classList.contains('split-panel-content')) return;
                const container = source.closest('.split-view-container');
                if (!container) return;
                const panels = container.querySelectorAll('.split-panel-content');
                if (panels.length < 2) return;
                const target = panels[0] === source ? panels[1] : panels[0];
                syncing = true;
                const max = source.scrollHeight - source.clientHeight;
                const ratio = max > 0 ? source.scrollTop / max : 0;
                target.scrollTop = ratio * (target.scrollHeight - target.clientHeight);
                syncing = false;
            }, true);
        }
    };
}

// Assembly entry point — merges all mixins into one Alpine.js component
function workflowApp() {
    return Object.assign({},
        workflowCoreMixin(),
        workflowOutlineMixin(),
        workflowCharactersMixin(),
        workflowChaptersMixin(),
        workflowPolishingMixin(),
        workflowProofreadingMixin(),
        workflowAutorunMixin(),
        workflowStateMixin()
    );
}
