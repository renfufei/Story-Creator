function checkResponse(r) {
    if (!r.ok) throw new Error('请求失败: ' + r.status);
    return r.json();
}

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
        _volumeMeta: [],
        chapterVolumeGroups: [],
        editing: false,
        stepGuidance: d.stepGuidance || '',
        stepGuidanceEdit: '',
        editingGuidance: false,
        guidanceLibraryItems: [],
        guidanceLibraryLoading: false,
        reuseFilterByStep: true,
        selectedGuidanceId: null,
        guidanceToastMsg: '',
        guidanceToastType: 'success',
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
            this.autoRunStrategy = d.autoRunStrategy || 'DEFAULT';
            this.autoRunConfigExpanded = localStorage.getItem('autoRunConfigExpanded') !== 'false';
            this.autoRunActiveStep = d.currentStep;
            this.autoRunActiveOrder = d.currentStepOrder;
            this.characterStateDims = d.characterStateDims;

            this.$watch('reuseFilterByStep', () => { this.loadGuidanceLibrary(); });
            this.loadChapterList();
            this.loadCharacterList();
            if (this.currentStep === 'OUTLINE_GENERATION') {
                this.loadOutlineData();
            }
            if (this.currentStep === 'PROOFREADING') {
                this.loadProofreadData();
            }
            if (['CHAPTER_WRITING', 'POLISHING', 'PROOFREADING'].includes(this.currentStep)) {
                this.loadVolumes();
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
                    .then(checkResponse)
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
                    .then(checkResponse)
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
                    .then(checkResponse)
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
                // Extract latest section marker to update streaming status
                if (step === 'OUTLINE_GENERATION' && e.data) {
                    const markers = e.data.match(/\[\[SECTION:\w+(?::[^\]]+)?\]\]/g);
                    if (markers && markers.length > 0) {
                        const lastMarker = markers[markers.length - 1];
                        const match = lastMarker.match(/\[\[SECTION:(\w+)(?::(.+))?\]\]/);
                        if (match) {
                            const type = match[1];
                            const params = match[2] ? match[2].split(':') : [];
                            if (type === 'REFINE') {
                                this.outlineStreamingStatus = `正在精修第${params[0]}章大纲...`;
                            } else if (type === 'CHAPTER') {
                                this.outlineStreamingStatus = `正在生成第${params[0]}章大纲...`;
                            } else if (type === 'VOLUME') {
                                this.outlineStreamingStatus = `正在生成第${params[0]}卷故事弧线...`;
                            } else if (type === 'SUMMARY') {
                                this.outlineStreamingStatus = '正在生成故事总纲...';
                            }
                        }
                    }
                }
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
            const matParam = this.getMaterialIdsParam ? this.getMaterialIdsParam() : '';
            const matSuffix = matParam ? '&' + matParam : '';
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
                fetch(`/projects/${this.projectId}/bg-gen/start?step=${this.currentStep}&chapter=0${matSuffix}`, {method:'POST'})
                    .then(checkResponse)
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
                fetch(`/projects/${this.projectId}/bg-gen/start?step=${this.currentStep}&chapter=0${matSuffix}`, {method:'POST'})
                    .then(checkResponse)
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
                .then(checkResponse)
                .then(data => {
                    this.chapterListData = data;
                    this._rebuildChapterVolumeGroups();
                })
                .catch(err => console.error('Failed to load chapters:', err));
        },

        loadVolumes() {
            fetch(`/projects/${this.projectId}/volumes`)
                .then(checkResponse)
                .then(data => {
                    this._volumeMeta = data;
                    this._rebuildChapterVolumeGroups();
                    if (this.currentStep === 'PROOFREADING' && this._rebuildProofreadVolumeGroups) {
                        this._rebuildProofreadVolumeGroups();
                    }
                })
                .catch(err => console.error('Failed to load volumes:', err));
        },

        _rebuildChapterVolumeGroups() {
            if (!this._volumeMeta || this._volumeMeta.length === 0) {
                this.chapterVolumeGroups = [];
                return;
            }
            let firstIncompleteFound = false;
            this.chapterVolumeGroups = this._volumeMeta.map(vol => {
                const chapters = this.chapterListData.filter(
                    ch => ch.chapterNumber >= vol.chapterStart && ch.chapterNumber <= vol.chapterEnd
                );
                const hasIncomplete = chapters.some(ch => {
                    if (this.currentStep === 'CHAPTER_WRITING') return ch.status !== 'CONFIRMED';
                    if (this.currentStep === 'POLISHING') return ch.polishStatus !== 'CONFIRMED';
                    return false;
                });
                let expanded = false;
                if (!firstIncompleteFound && hasIncomplete) {
                    expanded = true;
                    firstIncompleteFound = true;
                }
                if (!firstIncompleteFound && !hasIncomplete && chapters.length === 0) {
                    // skip
                } else if (!firstIncompleteFound) {
                    // all complete, don't expand
                }
                return {
                    volumeNumber: vol.volumeNumber,
                    title: vol.title,
                    chapterStart: vol.chapterStart,
                    chapterEnd: vol.chapterEnd,
                    expanded: expanded,
                    chapters: chapters
                };
            });
            // If no incomplete found, expand the last volume with chapters
            if (!firstIncompleteFound) {
                for (let i = this.chapterVolumeGroups.length - 1; i >= 0; i--) {
                    if (this.chapterVolumeGroups[i].chapters.length > 0) {
                        this.chapterVolumeGroups[i].expanded = true;
                        break;
                    }
                }
            }
        },

        volumeSummaryLabel(vol) {
            const total = vol.chapters.length;
            if (total === 0) return '';
            if (this.currentStep === 'CHAPTER_WRITING') {
                const done = vol.chapters.filter(ch => ch.status === 'CONFIRMED').length;
                return done + '/' + total + ' 已写';
            }
            if (this.currentStep === 'POLISHING') {
                const done = vol.chapters.filter(ch => ch.polishStatus === 'CONFIRMED').length;
                return done + '/' + total + ' 已润色';
            }
            return total + '章';
        },

        saveGuidance() {
            const formData = new FormData();
            formData.append('step', this.currentStep);
            formData.append('guidance', this.stepGuidanceEdit);
            fetch(`/projects/${this.projectId}/step-guidance`, {
                method: 'POST',
                body: formData
            })
            .then(checkResponse)
            .then(() => {
                this.stepGuidance = this.stepGuidanceEdit;
                this.editingGuidance = false;
            })
            .catch(err => alert('保存失败: ' + err));
        },

        confirmStep() {
            fetch(`/projects/${this.projectId}/confirm-only-ajax?step=${this.currentStep}`, {method:'POST'})
                .then(r => { if (!r.ok) throw new Error('请求失败'); return r.json(); })
                .then(data => {
                    if (data.status === 'ok') {
                        this.stepConfirmed = true;
                    } else {
                        alert(data.error || '确认失败');
                    }
                })
                .catch(err => alert('确认步骤失败: ' + err.message));
        },

        advanceStep() {
            fetch(`/projects/${this.projectId}/advance-ajax?step=${this.currentStep}`, {method:'POST'})
                .then(r => { if (!r.ok) throw new Error('请求失败'); return r.json(); })
                .then(data => {
                    if (data.status === 'ok') {
                        location.reload();
                    } else {
                        alert(data.error || '推进失败');
                    }
                })
                .catch(err => alert('推进步骤失败: ' + err.message));
        },

        currentStepLabel() {
            const found = this.stepList.find(s => s.name === this.currentStep);
            return found ? found.label : this.currentStep;
        },

        showGuidanceToast(msg, type) {
            this.guidanceToastMsg = msg;
            this.guidanceToastType = type || 'success';
            const toastEl = this.$refs.guidanceToast;
            if (toastEl) {
                const toast = new bootstrap.Toast(toastEl, { delay: 3000 });
                toast.show();
            }
        },

        saveGuidanceAsShared() {
            if (!this.stepGuidance) return;
            const formData = new FormData();
            formData.append('projectId', this.projectId);
            formData.append('step', this.currentStep);
            fetch('/settings/guidances/save-from-project', { method: 'POST', body: formData })
                .then(checkResponse)
                .then(data => {
                    if (data.error) {
                        this.showGuidanceToast(data.error, 'danger');
                    } else {
                        this.showGuidanceToast('已保存为通用指导: ' + data.name, 'success');
                    }
                })
                .catch(err => this.showGuidanceToast('保存失败: ' + err, 'danger'));
        },

        openReuseModal() {
            this.selectedGuidanceId = null;
            this.loadGuidanceLibrary();
            const modal = new bootstrap.Modal(document.getElementById('reuseGuidanceModal'));
            modal.show();
        },

        loadGuidanceLibrary() {
            this.guidanceLibraryLoading = true;
            let url = '/settings/guidances/list-json';
            if (this.reuseFilterByStep) {
                url += '?step=' + this.currentStep;
            }
            fetch(url)
                .then(checkResponse)
                .then(data => {
                    this.guidanceLibraryItems = data;
                    this.guidanceLibraryLoading = false;
                })
                .catch(() => {
                    this.guidanceLibraryItems = [];
                    this.guidanceLibraryLoading = false;
                });
        },

        applyReuseGuidance() {
            console.log('[applyReuseGuidance] called, selectedGuidanceId:', this.selectedGuidanceId, typeof this.selectedGuidanceId);
            if (!this.selectedGuidanceId) { console.log('[applyReuseGuidance] no selectedGuidanceId, returning'); return; }
            console.log('[applyReuseGuidance] guidanceLibraryItems ids:', this.guidanceLibraryItems.map(i => ({id: i.id, type: typeof i.id})));
            const item = this.guidanceLibraryItems.find(i => i.id == this.selectedGuidanceId);
            console.log('[applyReuseGuidance] found item:', item);
            if (!item) { console.log('[applyReuseGuidance] item not found, returning'); return; }
            const formData = new FormData();
            formData.append('step', this.currentStep);
            formData.append('guidance', item.guidance);
            fetch(`/projects/${this.projectId}/step-guidance`, { method: 'POST', body: formData })
                .then(checkResponse)
                .then(() => {
                    this.stepGuidance = item.guidance;
                    this.editingGuidance = false;
                    bootstrap.Modal.getInstance(document.getElementById('reuseGuidanceModal')).hide();
                    this.showGuidanceToast('已应用指导: ' + item.name, 'success');
                })
                .catch(err => this.showGuidanceToast('应用失败: ' + err, 'danger'));
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
        workflowStateMixin(),
        materialsMixin()
    );
}
