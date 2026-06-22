function workflowAutorunMixin() {
    return {
        autoMode: false,
        autoRunStepConfigs: {},
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
                .then(r => r.json())
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
                .then(r => r.json())
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
                .then(r => r.json())
                .then(() => { this.fullAutoStatus = '正在停止...'; });
        },

        openAutoRunStream() {
            fetch(`/projects/${this.projectId}/auto-run/stream-status`)
                .then(r => r.json())
                .then(data => {
                    if (!data.active) {
                        alert('当前没有活跃的自动运行流');
                        return;
                    }
                    this.autoRunStreamContent = '';
                    this.autoRunStreamStep = data.step || '';
                    this.autoRunStreamChapter = data.chapter || 0;
                    this.autoRunStreamOpen = true;
                    const modal = new bootstrap.Modal(document.getElementById('autoRunStreamModal'));
                    modal.show();

                    const es = new EventSource(`/projects/${this.projectId}/auto-run/stream`);
                    this.autoRunStreamEventSource = es;

                    es.addEventListener('step-info', (e) => {
                        const parts = e.data.split('|');
                        if (parts.length >= 2) {
                            this.autoRunStreamStep = parts[0];
                            this.autoRunStreamChapter = parseInt(parts[1]) || 0;
                        } else {
                            const colonParts = e.data.split(':');
                            this.autoRunStreamStep = colonParts[0] || '';
                            this.autoRunStreamChapter = parseInt(colonParts[1]) || 0;
                        }
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
                .then(r => r.json())
                .then(data => {
                    this.fullAutoStatus = data.progress || '';

                    if (data.step) {
                        const matched = this.stepList.find(s => s.label === data.step);
                        if (matched) {
                            this.autoRunActiveStep = matched.name;
                            this.autoRunActiveOrder = matched.order;
                        }
                    }

                    if (data.status === 'RUNNING' || data.status === 'STOPPING') {
                        this.fullAutoRunning = true;
                        if (data.step && data.step.includes('大纲') && this.outlineLoaded) {
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
