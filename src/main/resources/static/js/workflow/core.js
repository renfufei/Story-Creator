function checkResponse(r) {
    if (!r.ok) throw new Error('请求失败: ' + r.status);
    return r.json();
}

/**
 * 引导数据：异步 fetch。
 *
 * 原来 7 个 workflow 页面（枢纽页 + 6 个分步页）各自在 <head>/<body> 里跑一段**同步 XHR**，
 * 浏览器要停在 HTML 解析期等一个往返 —— JS 没开始跑、DOM 也没解析完，首屏白屏。
 * 现在统一走这里，由 workflowCoreMixin().init() await 之后再铺数据。
 *
 * URL 约定：
 *   · 分步页 /projects/{pid}/workflow/{step}      → /projects/{pid}/workflow/{step}/data
 *   · 枢纽页 /projects/{pid}/workflow[?step=XXX]  → /projects/{pid}/workflow/data[?step=XXX]
 *
 * ⚠️ 结果用 Object.assign **合并**进 window.__WORKFLOW_DATA__，不换对象：
 *    core.js 里 `const d = window.__WORKFLOW_DATA__` 捕获的是同一个引用。
 */
window.__WORKFLOW_DATA__ = window.__WORKFLOW_DATA__ || {};
window.loadWorkflowData = function () {
    if (!window.__workflowDataPromise) {
        const parts = location.pathname.split('/');
        const pid = parts[2];
        const stepMatch = location.pathname.match(/\/workflow\/([a-z-]+)$/);
        const queryStep = new URLSearchParams(location.search).get('step') || '';
        const url = stepMatch
            ? '/projects/' + pid + '/workflow/' + stepMatch[1] + '/data'
            : '/projects/' + pid + '/workflow/data'
                + (queryStep ? '?step=' + encodeURIComponent(queryStep) : '');
        window.__workflowDataPromise = fetch(url, { headers: { 'Accept': 'application/json' } })
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (data) {
                Object.assign(window.__WORKFLOW_DATA__, data || {});
                return window.__WORKFLOW_DATA__;
            })
            .catch(function (e) {
                // 退化：字段全空，页面仍能渲染（骨架收掉后是空态）
                console.error('workflow bootstrap failed: ' + (e && e.message ? e.message : e));
                return window.__WORKFLOW_DATA__;
            });
    }
    return window.__workflowDataPromise;
};

function workflowCoreMixin() {
    // 引导数据是异步的：这里先拿空壳，真正的字段在 init() await 之后由 applyBootstrap 铺进来。
    // 注意 d 捕获的是**同一个对象引用**（loadWorkflowData 用 Object.assign 合并，不换对象），
    // 所以任何早期读到的 d 都不会指向过期的那份。
    const d = window.__WORKFLOW_DATA__ || {};
    const pidFromPath = (location.pathname.match(/\/projects\/(\d+)/) || [])[1] || 0;
    return {
        // 引导数据还在路上：先出骨架
        booting: true,
        generating: false,
        generatedContent: '',
        existingContent: d.currentContent || '',
        editableContent: d.currentContent || '',
        projectId: d.projectId != null ? d.projectId : Number(pidFromPath),
        projectTitle: d.projectTitle || '',
        viewStepDisplayName: d.viewStepDisplayName || '',
        genreDisplayName: d.genreDisplayName || '',
        projectCurrentStepOrder: d.projectCurrentStepOrder || 1,
        isCurrentStep: d.isCurrentStep || false,
        hasNextStep: d.hasNextStep || false,
        modelConfigs: d.modelConfigs || [],
        stepModelConfigId: d.stepModelConfigId,
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
            { name: 'WORLD_BUILDING', label: '世界观设定', order: 1, route: 'world-building', icon: 'bi-globe2' },
            { name: 'CHARACTER_DESIGN', label: '角色设计', order: 2, route: 'characters', icon: 'bi-people' },
            { name: 'OUTLINE_GENERATION', label: '大纲生成', order: 3, route: 'outline', icon: 'bi-diagram-3' },
            { name: 'CHAPTER_WRITING', label: '分章节写作', order: 4, route: 'chapters', icon: 'bi-pencil-square' },
            { name: 'POLISHING', label: '润色修改', order: 5, route: 'polishing', icon: 'bi-magic' },
            { name: 'PROOFREADING', label: '校对精修', order: 6, route: 'proofreading', icon: 'bi-check2-square' },
        ],

        /**
         * 把引导数据铺进组件状态。
         * 这些字段原来在构造期直接从 window.__WORKFLOW_DATA__ 取，
         * 异步化之后只能等 await 回来再赋值，所以集中到这里（init 里调用一次）。
         */
        applyBootstrap(boot) {
            const dd = boot || {};
            this.existingContent = dd.currentContent || '';
            this.editableContent = dd.currentContent || '';
            if (dd.projectId != null) this.projectId = dd.projectId;
            this.projectTitle = dd.projectTitle || '';
            this.viewStepDisplayName = dd.viewStepDisplayName || '';
            this.genreDisplayName = dd.genreDisplayName || '';
            this.projectCurrentStepOrder = dd.projectCurrentStepOrder || 1;
            this.isCurrentStep = dd.isCurrentStep || false;
            this.hasNextStep = dd.hasNextStep || false;
            this.modelConfigs = dd.modelConfigs || [];
            this.stepModelConfigId = dd.stepModelConfigId;
            this.currentStep = dd.currentStep;
            this.currentStepOrder = dd.currentStepOrder;
            this.stepConfirmed = dd.stepConfirmed;
            this.isLastStep = dd.isLastStep;
            this.totalChapters = dd.totalChapters;
            this.chapterWordCount = dd.chapterWordCount;
            this.chapterWordCountMin = dd.chapterWordCountMin;
            this.chapterWordCountMax = dd.chapterWordCountMax;
            this.stepGuidance = dd.stepGuidance || '';
            this.wfStateCurrentStep = dd.projectCurrentStep;
            this.autoMode = dd.autoMode;
            this.autoRunStepConfigs = dd.autoRunStepConfigs;
            this.autoRunStrategy = dd.autoRunStrategy || 'DEFAULT';
            this.autoRunActiveStep = dd.currentStep;
            this.autoRunActiveOrder = dd.currentStepOrder;
            this.characterStateDims = dd.characterStateDims;
        },

        init() {
            // $watch 必须留在 init 里同步注册（Alpine 的作用域只在 init 执行期有效，
            // await 之后再注册就挂不到组件作用域上）
            this.$watch('reuseFilterByStep', () => { this.loadGuidanceLibrary(); });
            return this.boot();
        },

        async boot() {
            // 引导数据先到位：后面几个 load* 都依赖 currentStep / projectId
            try {
                const boot = (typeof window.loadWorkflowData === 'function')
                    ? await window.loadWorkflowData()
                    : (window.__WORKFLOW_DATA__ || {});
                this.applyBootstrap(boot);
                this.autoRunConfigExpanded = localStorage.getItem('autoRunConfigExpanded') !== 'false';

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
            } finally {
                // ★ 数据铺完才收骨架：先收的话空列表会把刚发出的操作冲掉
                this.booting = false;
            }
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
                // 后端下发的 chapterNumbers 优先：分卷管理页调整过归属后，某卷的章节可能不再是连续区间
                const chapters = Array.isArray(vol.chapterNumbers)
                    ? this.chapterListData.filter(ch => vol.chapterNumbers.indexOf(ch.chapterNumber) >= 0)
                    : this.chapterListData.filter(
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

        regenerateWorldFacets() {
            fetch(`/projects/${this.projectId}/world-facets/regenerate`, {method:'POST'})
                .then(r => r.json())
                .then(data => {
                    if (data.error) {
                        alert(data.error);
                    } else {
                        alert(data.message || '分面展开已启动');
                    }
                })
                .catch(err => alert('操作失败: ' + err.message));
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

        /* ===== 前后对照面板的通用封装（实现见 js/compare-panel.js） =====
           扩写 / 润色 / 精修 三个页面用的是同一套面板：句级差异高亮、字数增量徽标、
           左右栏按占比同步滚动。这里只做薄封装，算法只有一份，别在页面里重写。 */
        diffClass(kind) {
            return window.SCCmp ? SCCmp.diffClass(kind) : '';
        },

        syncPreviewScroll(ev) {
            if (window.SCCmp) SCCmp.syncScroll(ev);
        },

        /** @param pair 形如 {beforeCount, afterCount} 的对照数据（页面里通常是 polishPreviews[n] 这类） */
        previewDeltaClass(pair) {
            if (!pair || !window.SCCmp) return 'bg-secondary';
            return SCCmp.deltaClass(pair.beforeCount, pair.afterCount);
        },

        previewDeltaText(pair) {
            if (!pair || !window.SCCmp) return '';
            return SCCmp.deltaText(pair.beforeCount, pair.afterCount);
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

// ---- 拆分后的按步骤入口 ----
// 页面路由与后端 ROUTE_TO_STEP 保持一致。
// 注意：各分步页的共享布局（头部按钮 + 全局替换/修改意见等模态框）引用了
// 几乎所有 mixin 的成员——loadCharacterList（characters）、polishNoteChapter（polishing）、
// globalReplacing/openGlobalReplace（proofreading）等，缺任一个都会在 init/render 阶段
// 抛 ReferenceError 并中断数据加载（曾致大纲页空白）。
// 因此每个分步 app 与 workflowApp 一样合并全部 mixin；保留命名入口，
// 待未来把共享布局抽成独立组件后再按页精简。
function worldBuildingApp() {
    return workflowApp();
}
function charactersApp() {
    return workflowApp();
}
function outlineApp() {
    return workflowApp();
}
function chaptersApp() {
    return workflowApp();
}
function polishingApp() {
    return workflowApp();
}
function proofreadingApp() {
    return workflowApp();
}
