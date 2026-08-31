const AUTO_RUN_STEP_LABELS = {
    'WORLD_BUILDING': '世界观设定',
    'CHARACTER_DESIGN': '角色设计',
    'CHARACTER_OVERVIEW': '角色总览',
    'CHARACTER_CARD': '角色卡生成',
    'CHARACTER_REFINE': '角色精修',
    'OUTLINE_GENERATION': '大纲生成',
    'VOLUME_ARC': '卷弧线生成',
    'CHAPTER_OUTLINE': '章节大纲',
    'CHAPTER_OUTLINE_REFINE': '章节大纲精修',
    'STORY_SUMMARY': '故事总纲',
    'CHAPTER_WRITING': '章节写作',
    'WRITING_PRIMARY': '写作核心',
    'CHARACTER_STATES': '角色状态',
    'CONTEXT_BRIEFING': '前文梳理',
    'POLISHING': '润色修改',
    'PROOFREADING': '校对报告',
    'PROOFREAD_PLOT_SUMMARY': '情节摘要',
    'PROOFREAD_FORESHADOWING': '伏笔检查',
    'PROOFREAD_FIX': '校对精修',
};

const AUTO_RUN_STRATEGY_LABELS = {
    'DEFAULT': '标准创作模式',
};

const STEP_ORDERED_SUB_STEPS = {
    DEFAULT: {
        WORLD_BUILDING: [],
        CHARACTER_DESIGN: ['CHARACTER_CARD', 'CHARACTER_OVERVIEW', 'CHARACTER_REFINE'],
        OUTLINE_GENERATION: ['VOLUME_ARC', 'CHAPTER_OUTLINE', 'CHAPTER_OUTLINE_REFINE', 'STORY_SUMMARY'],
        CHAPTER_WRITING: ['CONTEXT_BRIEFING', 'WRITING_PRIMARY', 'CHARACTER_STATES'],
        POLISHING: [],
        PROOFREADING: ['PROOFREAD_PLOT_SUMMARY', 'PROOFREAD_FORESHADOWING', 'PROOFREAD_FIX'],
    },
};

const STEP_PRIMARY_LABELS = {
    WORLD_BUILDING: '世界观核心',
    CHARACTER_DESIGN: null,
    OUTLINE_GENERATION: null,
    CHAPTER_WRITING: null,
    POLISHING: '润色核心',
    PROOFREADING: null,
};

const STEP_LOCKED_SUB_STEPS = {
    CHARACTER_DESIGN: ['CHARACTER_OVERVIEW', 'CHARACTER_CARD'],
    CHAPTER_WRITING: ['WRITING_PRIMARY'],
    OUTLINE_GENERATION: ['VOLUME_ARC', 'CHAPTER_OUTLINE', 'STORY_SUMMARY'],
};

function workflowAutorunMixin() {
    return {
        autoMode: false,
        autoRunStepConfigs: {},
        autoRunStrategy: 'DEFAULT',
        autoRunConfigExpanded: true,
        fullAutoRunning: false,
        fullAutoStatus: '',
        fullAutoError: '',
        autoRunPollTimer: null,
        autoRunActiveStep: '',
        autoRunActiveOrder: 1,
        autoRunStreamOpen: false,
        autoRunStreamContent: '',
        autoRunStreamStep: '',
        autoRunStreamChapter: 0,
        autoRunStreamEventSource: null,

        checkAutoRunStatus() {
            fetch(`/projects/${this.projectId}/auto-run/status`)
                .then(checkResponse)
                .then(data => {
                    if (data.status === 'RUNNING' || data.status === 'STOPPING') {
                        this.fullAutoRunning = true;
                        this.fullAutoStatus = data.progress || '运行中...';
                        this.fullAutoError = '';
                        this.startAutoRunPolling();
                    } else if (data.status === 'FAILED') {
                        this.fullAutoError = data.error || '未知错误';
                    }
                });
        },

        startFullAuto() {
            this.fullAutoError = '';
            fetch(`/projects/${this.projectId}/auto-run/start`, { method: 'POST' })
                .then(checkResponse)
                .then(data => {
                    if (data.status === 'ok') {
                        this.fullAutoRunning = true;
                        this.fullAutoStatus = '正在启动...';
                        this.startAutoRunPolling();
                    } else {
                        this.fullAutoError = data.message || '启动失败';
                    }
                })
                .catch(err => { this.fullAutoError = '请求失败: ' + err.message; });
        },

        stopFullAuto() {
            fetch(`/projects/${this.projectId}/auto-run/stop`, { method: 'POST' })
                .then(checkResponse)
                .then(() => { this.fullAutoStatus = '正在停止...'; });
        },

        openAutoRunStream() {
            fetch(`/projects/${this.projectId}/auto-run/stream-status`)
                .then(checkResponse)
                .then(data => {
                    if (!data.active) {
                        alert('当前没有活跃的自动运行流');
                        return;
                    }
                    this.autoRunStreamContent = '';
                    const initStep = data.step || '';
                    this.autoRunStreamStep = AUTO_RUN_STEP_LABELS[initStep] || initStep;
                    this.autoRunStreamChapter = data.chapter || 0;
                    this.autoRunStreamOpen = true;
                    const modal = new bootstrap.Modal(document.getElementById('autoRunStreamModal'));
                    modal.show();

                    const es = new EventSource(`/projects/${this.projectId}/auto-run/stream`);
                    this.autoRunStreamEventSource = es;

                    es.addEventListener('step-info', (e) => {
                        let rawStep = '';
                        let chapter = 0;
                        const parts = e.data.split('|');
                        if (parts.length >= 2) {
                            rawStep = parts[0];
                            chapter = parseInt(parts[1]) || 0;
                        } else {
                            const colonParts = e.data.split(':');
                            rawStep = colonParts[0] || '';
                            chapter = parseInt(colonParts[1]) || 0;
                        }
                        this.autoRunStreamStep = AUTO_RUN_STEP_LABELS[rawStep] || rawStep;
                        this.autoRunStreamChapter = chapter;
                        this.autoRunStreamContent = '';
                    });

                    es.addEventListener('replay-buffer', (e) => {
                        this.autoRunStreamContent = e.data;
                        this.$nextTick(() => {
                            if (this.$refs.autoRunStreamOutput) {
                                this.$refs.autoRunStreamOutput.scrollTop = this.$refs.autoRunStreamOutput.scrollHeight;
                            }
                        });
                    });

                    es.addEventListener('token', (e) => {
                        this.autoRunStreamContent += e.data;
                        if (this.$refs.autoRunStreamOutput) {
                            this.$refs.autoRunStreamOutput.scrollTop = this.$refs.autoRunStreamOutput.scrollHeight;
                        }
                    });

                    es.addEventListener('done', () => {
                        es.close();
                        this.autoRunStreamEventSource = null;
                    });

                    es.addEventListener('error', (e) => {
                        es.close();
                        this.autoRunStreamEventSource = null;
                    });

                    es.onerror = () => {
                        es.close();
                        this.autoRunStreamEventSource = null;
                    };
                })
                .catch(() => alert('获取流状态失败'));
        },

        closeAutoRunStream() {
            if (this.autoRunStreamEventSource) {
                this.autoRunStreamEventSource.close();
                this.autoRunStreamEventSource = null;
            }
            this.autoRunStreamOpen = false;
        },

        currentStrategyLabel() {
            return AUTO_RUN_STRATEGY_LABELS[this.autoRunStrategy] || this.autoRunStrategy;
        },

        stepPrimaryLabel(stepName) {
            return STEP_PRIMARY_LABELS[stepName] || null;
        },

        orderedSubStepsForStep(stepName) {
            const mapping = STEP_ORDERED_SUB_STEPS[this.autoRunStrategy] || STEP_ORDERED_SUB_STEPS['DEFAULT'];
            return mapping[stepName] || [];
        },

        isSubStepLocked(sub) {
            for (const locked of Object.values(STEP_LOCKED_SUB_STEPS)) {
                if (locked.includes(sub)) return true;
            }
            return false;
        },

        isMainStepEnabled(stepName) {
            return this.autoRunStepConfigs[stepName] !== false;
        },

        toggleStepConfig(stepName, enabled) {
            this.autoRunStepConfigs[stepName] = enabled;
            fetch(`/projects/${this.projectId}/auto-run/step-config?step=${stepName}&enabled=${enabled}`, { method: 'PUT' })
                .catch(() => { this.autoRunStepConfigs[stepName] = !enabled; });
        },

        startAutoRunPolling() {
            if (this.autoRunPollTimer) return;
            this.autoRunPollTimer = setInterval(() => this.pollAutoRunStatus(), 2000);
        },

        stopAutoRunPolling() {
            if (this.autoRunPollTimer) {
                clearInterval(this.autoRunPollTimer);
                this.autoRunPollTimer = null;
            }
        },

        pollAutoRunStatus() {
            fetch(`/projects/${this.projectId}/auto-run/status`)
                .then(checkResponse)
                .then(data => {
                    this.fullAutoStatus = data.progress || '';

                    if (data.step) {
                        const matched = this.stepList.find(s => s.name === data.step || s.label === data.step);
                        if (matched) {
                            this.autoRunActiveStep = matched.name;
                            this.autoRunActiveOrder = matched.order;
                        }
                    }

                    if (data.status === 'RUNNING' || data.status === 'STOPPING') {
                        this.fullAutoRunning = true;
                        if (data.step && (data.step === 'OUTLINE_GENERATION' || data.step.includes('大纲')) && this.outlineLoaded) {
                            this.loadOutlineData(true);
                        }
                    } else {
                        this.fullAutoRunning = false;
                        this.stopAutoRunPolling();

                        if (data.status === 'COMPLETED') {
                            this.fullAutoStatus = '全自动创作完成！即将刷新...';
                            setTimeout(() => location.reload(), 2000);
                        } else if (data.status === 'FAILED') {
                            this.fullAutoError = data.error || '未知错误';
                            this.fullAutoStatus = '即将刷新页面...';
                            setTimeout(() => location.reload(), 2500);
                        } else {
                            this.fullAutoStatus = '自动创作已停止，即将刷新...';
                            setTimeout(() => location.reload(), 1500);
                        }
                    }
                })
                .catch(() => {});
        }
    };
}
