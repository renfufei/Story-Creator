/**
 * 逆向工程流程页共享逻辑（/projects/{id}/reverse/options 与 /progress 两个页面复用）。
 *
 * 拆分自原 /import/txt 单页的第 3/4 步：引导数据由
 * GET /projects/{projectId}/reverse/data 注入 window.__REVERSE_DATA__（同步 XHR，先于 Alpine 解析）。
 * 业务接口（reverse-plan / start-reverse / stream / stop）仍走 /import/txt/{jobId}/...。
 */

/** 两个页面共用的状态与方法（Alpine mixin）。 */
function reverseMixin() {
    return {
        // 流程控制：来自 /reverse-plan 的扫描结果
        plan: null,
        phases: [],
        interrupted: false,
        phaseLabels: {
            'CHAPTER_OUTLINE': '章节大纲',
            'STORY_ARC': '故事弧线',
            'WORLD': '世界观',
            'CHARACTERS': '角色',
            'CHARACTER_CARDS': '角色卡片',
            'STORY_OUTLINE': '总纲'
        },

        /** 进入页面 / 操作后扫描一次完成度，决定按钮文案与提示。 */
        async refreshPlan() {
            if (!this.jobId) return;
            try {
                const resp = await fetch('/import/txt/' + this.jobId + '/reverse-plan');
                const p = await resp.json();
                if (p.error) { this.plan = null; return; }
                this.plan = p;
                this.interrupted = !!p.interrupted;
                this.applyPlanPhases(p.phases);
            } catch (e) {
                this.plan = null;
            }
        },

        /** 清空已完成的逆向工程产出，回到未开始状态。 */
        async resetPlan() {
            if (!confirm('确定清空已生成的章节大纲、故事弧线、世界观、角色与总纲吗？此操作不可撤销。')) return;
            const resp = await fetch('/import/txt/' + this.jobId + '/reverse-reset', {method: 'POST'});
            const data = await resp.json();
            if (data.error) { alert(data.error); return; }
            await this.refreshPlan();
        },

        applyPlanPhases(phases) {
            this.phases = (phases || []).map(p => ({
                phase: p.phase,
                label: this.phaseLabels[p.phase] || p.label,
                fullLabel: p.label,
                status: p.status,
                totalUnits: p.totalUnits,
                completedUnits: p.completedUnits,
                runnable: p.runnable
            }));
        },

        get planHeadline() {
            if (!this.plan) return '';
            if (this.plan.completed) return '该项目的逆向工程已全部完成';
            if (this.interrupted) return '上次执行被中断，可继续完成剩余部分';
            if (this.plan.resumable) return '检测到未完成的进度';
            return '尚未开始';
        },

        get planSubline() {
            if (!this.plan) return '';
            return '已完成 ' + (this.plan.completedUnits || 0) + ' / ' + (this.plan.totalUnits || 0)
                + ' 项 · 共 ' + (this.plan.totalChapters || 0) + ' 章 / '
                + (this.plan.totalVolumes || 0) + ' 卷（每卷 ' + (this.plan.chaptersPerVolume || 0) + ' 章）';
        },

        get startButtonText() {
            if (!this.plan) return '开始逆向工程';
            if (this.plan.completed) return '全部已完成（可清空进度重跑）';
            if (this.plan.resumable || this.interrupted) {
                const remain = (this.plan.totalUnits || 0) - (this.plan.completedUnits || 0);
                return '继续执行（剩余 ' + remain + ' 项）';
            }
            return '开始逆向工程';
        },

        phaseBadge(p) {
            if (!p.runnable) return 'bg-light text-muted border';
            switch (p.status) {
                case 'COMPLETED': return 'bg-success';
                case 'RUNNING': return 'bg-primary';
                case 'PARTIAL': return 'bg-warning text-dark';
                case 'FAILED': return 'bg-danger';
                default: return 'bg-secondary';
            }
        },

        phaseCircleClass(p) {
            switch (p.status) {
                case 'COMPLETED': return 'bg-success text-white';
                case 'RUNNING': return 'bg-primary text-white';
                case 'PARTIAL': return 'bg-warning text-dark';
                case 'FAILED': return 'bg-danger text-white';
                case 'SKIPPED': return 'bg-light text-muted border';
                default: return 'bg-light text-muted border';
            }
        },

        phaseCircleIcon(p) {
            switch (p.status) {
                case 'COMPLETED': return 'bi-check';
                case 'RUNNING': return 'bi-arrow-repeat';
                case 'FAILED': return 'bi-x';
                case 'SKIPPED': return 'bi-dash';
                default: return 'bi-circle';
            }
        },

        /** 阶段流转：把当前阶段标为运行中，之前的未完成阶段视为已完成。 */
        advancePhase(name) {
            let reached = false;
            this.phases.forEach(p => {
                if (p.phase === name) {
                    p.status = 'RUNNING';
                    reached = true;
                } else if (!reached && p.runnable && p.status === 'RUNNING') {
                    p.status = 'COMPLETED';
                }
            });
        },

        markPhaseDone(name) {
            const p = this.phases.find(x => x.phase === name);
            if (p) { p.status = 'COMPLETED'; p.completedUnits = p.totalUnits; }
        },

        markPhaseSkipped(name) {
            const p = this.phases.find(x => x.phase === name);
            if (p) { p.status = p.completedUnits > 0 ? 'COMPLETED' : 'SKIPPED'; }
        },

        // ---------- SSE 载荷解码 ----------

        decodePayload(b64) {
            try {
                const bin = atob(b64);
                const bytes = Uint8Array.from(bin, c => c.charCodeAt(0));
                const text = new TextDecoder('utf-8').decode(bytes);
                try { return JSON.parse(text); } catch (e) { return text; }
            } catch (e) {
                return null;
            }
        },

        /** 断线重连的 replay-buffer 是原始标记流，去掉控制标记只保留正文。 */
        stripControlMarkers(s) {
            return (s || '')
                .replace(/\[\[RE_[A-Z_]+:[^\]]*\]\]/g, '')
                .replace(/\[\[BG_[A-Z_]+\]\]/g, '');
        },

        scrollOutput() {
            this.$nextTick(() => {
                const el = this.$refs.output;
                if (el) el.scrollTop = el.scrollHeight;
            });
        },

        formatWordCount(n) {
            if (n >= 10000) return (n / 10000).toFixed(1) + '万';
            return n;
        }
    };
}

/** 逆向选项页（原第 3 步）。 */
function reverseOptions() {
    var BOOT = window.__REVERSE_DATA__ || {};
    return Object.assign({}, reverseMixin(), {
        bootError: BOOT.error || '',
        jobId: BOOT.jobId || null,
        jobTitle: BOOT.title || '',
        jobStatus: BOOT.status || '',
        projectId: (location.pathname.match(/\/projects\/(\d+)/) || [])[1],
        // 逆向选项（初值取自 job 上次保存的选项，可修改后重新启动）
        runWorldBuilding: BOOT.runWorldBuilding !== false,
        runCharacters: BOOT.runCharacters !== false,
        runOutline: BOOT.runOutline !== false,
        chaptersPerVolume: BOOT.chaptersPerVolume || 30,
        modelConfigId: BOOT.modelConfigId || '',
        modelConfigs: BOOT.modelConfigs || [],
        starting: false,

        init() {
            if (this.jobId) this.refreshPlan();
        },

        /** 启动（或继续）逆向工程，成功后跳转到独立的执行监控页。 */
        async startReverse() {
            if (this.starting) return;
            this.starting = true;
            try {
                const resp = await fetch('/import/txt/' + this.jobId + '/start-reverse', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({
                        runWorldBuilding: this.runWorldBuilding,
                        runCharacters: this.runCharacters,
                        runOutline: this.runOutline,
                        chaptersPerVolume: this.chaptersPerVolume,
                        modelConfigId: this.modelConfigId ? parseInt(this.modelConfigId) : null
                    })
                });
                const data = await resp.json();
                if (data.error) { alert(data.error); return; }
                location.href = '/projects/' + this.projectId + '/reverse/progress';
            } catch (e) {
                alert('启动失败: ' + e.message);
            } finally {
                this.starting = false;
            }
        }
    });
}

/** 执行监控页（原第 4 步）：重开时自动恢复实时监控或展示最终状态。 */
function reverseProgress() {
    var BOOT = window.__REVERSE_DATA__ || {};
    return Object.assign({}, reverseMixin(), {
        bootError: BOOT.error || '',
        jobId: BOOT.jobId || null,
        jobTitle: BOOT.title || '',
        projectId: (location.pathname.match(/\/projects\/(\d+)/) || [])[1],
        output: '',
        active: false,
        done: false,
        phaseText: '等待中',
        phaseBadgeClass: 'bg-secondary',
        progressText: '',
        // 防止 init() 重复调用（Alpine 自动调用 init() + 模板 x-init="init()"）导致重复打开 SSE
        esRef: null,
        // 断线自动重连：指数退避，最多 5 次；重连成功后由后端 replay-buffer 恢复完整输出
        reconnectAttempts: 0,
        reconnectTimer: null,
        terminal: false,

        init() {
            if (!this.jobId) return;
            this.refreshPlan().then(() => this.watchStatus());
        },

        /** 查询任务是否在执行：在跑则接 SSE 实时流，否则展示静态状态。 */
        async watchStatus() {
            try {
                const resp = await fetch('/import/txt/' + this.jobId + '/status');
                const d = await resp.json();
                if (d.active) {
                    this.active = true;
                    this.reconnectAttempts = 0; // 状态探测成功，连接没问题
                    this.connectSSE();
                    return;
                }
                // 静态兜底：按 plan / 状态推断展示文案
                if (this.plan && this.plan.completed) {
                    this.done = true;
                    this.phaseText = '已完成';
                    this.phaseBadgeClass = 'bg-success';
                } else if (this.interrupted) {
                    this.phaseText = '已中断（可继续）';
                    this.phaseBadgeClass = 'bg-warning';
                } else if (this.plan && this.plan.completedUnits > 0) {
                    this.phaseText = '部分完成（可继续）';
                    this.phaseBadgeClass = 'bg-warning';
                } else if ((d.status || '').startsWith('RE_')) {
                    this.phaseText = '执行已结束';
                    this.phaseBadgeClass = 'bg-secondary';
                } else {
                    this.phaseText = '尚未开始';
                    this.phaseBadgeClass = 'bg-secondary';
                }
            } catch (e) {
                this.phaseText = '状态查询失败';
                this.phaseBadgeClass = 'bg-danger';
                if (this.reconnectAttempts > 0) this.scheduleReconnect(); // 重连期间探测失败：继续退避重试
            }
        },

        connectSSE() {
            if (this.esRef) return; // 已连接，避免重复订阅导致输出翻倍
            const es = new EventSource('/import/txt/' + this.jobId + '/stream');
            this.esRef = es;
            es.addEventListener('token', (e) => {
                this.output += e.data;
                this.scrollOutput();
            });
            es.addEventListener('replay-buffer', (e) => {
                this.reconnectAttempts = 0; // 重放成功 = 已重新接上
                this.output = this.stripControlMarkers(e.data);
                this.scrollOutput();
            });
            // 任务计划（各阶段完成度）——进入流程时下发一次
            es.addEventListener('plan', (e) => {
                const p = this.decodePayload(e.data);
                if (!p) return;
                this.plan = {
                    completedUnits: p.completedUnits,
                    totalUnits: p.totalUnits,
                    chaptersPerVolume: p.chaptersPerVolume,
                    totalChapters: p.totalChapters,
                    totalVolumes: p.totalVolumes,
                    resumable: p.resumable,
                    completed: p.completed
                };
                this.interrupted = false;
                this.applyPlanPhases(p.phases);
                this.output += '=== 执行计划：已完成 ' + p.completedUnits + '/' + p.totalUnits
                    + ' 项，本章节数 ' + p.totalChapters + '，分 ' + p.totalVolumes + ' 卷 ===\n';
                this.scrollOutput();
            });
            es.addEventListener('phase', (e) => {
                const name = (e.data || '').replace('[[RE_PHASE:', '').replace(']]', '').trim();
                this.advancePhase(name);
                const p = this.phases.find(x => x.phase === name);
                this.phaseText = p ? p.label : name;
                this.phaseBadgeClass = 'bg-primary';
                this.progressText = '';
                this.output += '\n\n===== ' + (p ? p.fullLabel : name) + ' =====\n';
                this.scrollOutput();
            });
            es.addEventListener('phase-done', (e) => {
                const parts = (e.data || '').split('|');
                this.markPhaseDone(parts[0]);
                this.progressText = '';
                this.output += '\n[阶段完成] ' + parts[0] + ' ' + parts[1] + '/' + parts[2] + '\n';
                this.scrollOutput();
            });
            es.addEventListener('phase-skip', (e) => {
                const name = (e.data || '').trim();
                this.markPhaseSkipped(name);
                const p = this.phases.find(x => x.phase === name);
                this.output += '\n[跳过] ' + (p ? p.fullLabel : name) + '\n';
                this.scrollOutput();
            });
            es.addEventListener('note', (e) => {
                const n = this.decodePayload(e.data);
                const text = typeof n === 'string' ? n : (n && n.text) || '';
                if (text) {
                    this.output += '· ' + text + '\n';
                    this.scrollOutput();
                }
            });
            // 单元产出：实时展示章节大纲 / 故事弧线 / 汇总分块
            es.addEventListener('item', (e) => {
                const it = this.decodePayload(e.data);
                if (!it) return;
                let block = '\n';
                if (it.phase === 'CHAPTER_OUTLINE') {
                    block += '【' + (it.skipped ? '已跳过 ' : '') + '第' + it.index + '章】' + (it.key || '') + '\n';
                    block += '标题：' + (it.title || '（无）') + '\n';
                    if (it.characters) block += '角色：' + it.characters + '\n';
                    block += '大纲：' + (it.body || '') + '\n';
                } else {
                    block += '【' + (it.skipped ? '已跳过 ' : '') + it.index + '/' + it.total + '】' + (it.key || '') + '\n';
                    if (it.title) block += '名称：' + it.title + '\n';
                    block += (it.body || '') + '\n';
                }
                this.output += block;
                this.scrollOutput();
            });
            es.addEventListener('progress', (e) => {
                this.progressText = e.data;
                const parts = (e.data || '').split('/');
                if (parts.length === 2) {
                    const idx = this.phases.findIndex(p => p.status === 'RUNNING');
                    if (idx >= 0) {
                        this.phases[idx].completedUnits = parseInt(parts[0]) || 0;
                        this.phases[idx].totalUnits = parseInt(parts[1]) || this.phases[idx].totalUnits;
                    }
                }
            });
            es.addEventListener('done', () => {
                this.terminal = true;
                this.active = false;
                this.done = true;
                this.phaseText = '完成';
                this.phaseBadgeClass = 'bg-success';
                this.progressText = '';
                this.phases.forEach(p => { if (p.runnable) p.status = 'COMPLETED'; });
                this.output += '\n\n=== 逆向工程完成 ===\n';
                this.scrollOutput();
                es.close();
                this.esRef = null;
            });
            // 注意区分两种 error 事件：服务端业务错误（带 data，任务真的失败了）与
            // EventSource 传输层错误（无 data，连接断了 —— 此时应自动重连，而不是吓用户）。
            // 后端所有业务 error 事件都带非空 data（SseErrorHelper 兜底文案），以此区分。
            es.addEventListener('error', (e) => {
                if (e.data == null) return; // 传输层断连：交给 es.onerror 走自动重连
                this.terminal = true;
                this.active = false;
                this.phaseText = '错误';
                this.phaseBadgeClass = 'bg-danger';
                this.output += '\n\n[错误] ' + e.data + '\n';
                this.scrollOutput();
                es.close();
                this.esRef = null;
            });
            es.addEventListener('stopped', () => {
                this.terminal = true;
                this.active = false;
                this.interrupted = true;
                this.phaseText = '已停止（可继续）';
                this.phaseBadgeClass = 'bg-warning';
                this.output += '\n\n[已停止] 已完成的进度已保留，可到「逆向选项」继续执行。\n';
                this.scrollOutput();
                es.close();
                this.esRef = null;
            });
            es.onerror = () => {
                // 传输层断连（emitter 超时 / 网络抖动 / 代理切断）。EventSource 的原生重连
                // 被我们 close() 掉了，改为自己的带退避重连：先探测 /status，任务仍在跑才重接；
                // 后端会用 replay-buffer 补齐全部输出，内容不丢不重。
                es.close();
                this.esRef = null;
                this.scheduleReconnect();
            };
        },

        scheduleReconnect() {
            if (this.terminal || this.done) return;
            if (this.reconnectTimer) return; // 已在等待，避免 onerror 与 error 事件双跳重连
            if (this.reconnectAttempts >= 5) {
                this.active = false;
                this.phaseText = '监控已断开';
                this.phaseBadgeClass = 'bg-secondary';
                this.output += '\n\n[提示] 实时监控已断开（自动重连 5 次未成功），可刷新页面重新连接；后台任务不受影响。\n';
                this.scrollOutput();
                return;
            }
            const attempt = ++this.reconnectAttempts;
            const delay = Math.min(2000 * Math.pow(2, attempt - 1), 15000);
            this.phaseText = '连接中断，' + Math.round(delay / 1000) + ' 秒后自动重连（第 ' + attempt + '/5 次）';
            this.phaseBadgeClass = 'bg-warning';
            this.reconnectTimer = setTimeout(() => {
                this.reconnectTimer = null;
                this.watchStatus();
            }, delay);
        },

        destroy() {
            if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
        },

        async stopReverse() {
            await fetch('/import/txt/' + this.jobId + '/stop', {method: 'POST'});
            this.terminal = true;
            this.active = false;
            this.interrupted = true;
            this.phaseText = '已停止（可继续）';
            this.phaseBadgeClass = 'bg-warning';
            // 稍等后台线程收尾，再重新扫描一次真实完成度
            setTimeout(() => this.refreshPlan(), 1500);
        }
    });
}
