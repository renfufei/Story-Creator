function inspectChapter() {
    // 引导数据是异步的：这里先拿空壳（loadInspectChapterData 用 Object.assign 合并，不换对象），
    // 真正的字段在 boot() await 之后铺进来。
    const data = window.__inspectData || {};
    const parts = location.pathname.split('/');
    const pidFromPath = parts[2];
    const numFromPath = parts[5];

    /** 侧边 tab：availability 来自引导数据里的 fieldAvail */
    function buildTabs(avail) {
        const fa = avail || {};
        return [
            { field: 'outlineSummary', label: '章节大纲', available: !!fa.outlineSummary },
            { field: 'writingBriefing', label: '写作简报', available: !!fa.writingBriefing },
            { field: 'eventPlan', label: '事件计划', available: !!fa.eventPlan },
            { field: 'content', label: '正文内容', available: !!fa.content },
            { field: 'contentDraft', label: '原稿对比', available: !!fa.contentDraft },
            { field: 'contentSummary', label: '内容摘要', available: !!fa.contentSummary },
            { field: 'characterStates', label: '角色状态', available: !!fa.characterStates },
        ];
    }

    return {
        // 引导数据还在路上：先盖骨架
        booting: true,
        sidebarOpen: window.innerWidth > 768,
        activeTab: null,
        loading: false,
        cache: {},
        projectId: data.projectId != null ? data.projectId : Number(pidFromPath),
        projectTitle: data.projectTitle || '',
        chapterNum: data.chapterNum != null ? data.chapterNum : Number(numFromPath),
        chapterTitle: '',
        chapterMetas: [],
        prevNum: null,
        nextNum: null,
        tabs: [],

        /**
         * 引导数据是异步的：await 之后铺字段 + 选第一个可用 tab，最后才收骨架。
         * 页面用 x-init="boot()" 显式调用（不再留 init()，避免 Alpine 自动调用与 x-init 双跑）。
         */
        async boot() {
            try {
                const d = (typeof window.loadInspectChapterData === 'function')
                    ? await window.loadInspectChapterData()
                    : (window.__inspectData || {});
                if (d.projectId != null) this.projectId = d.projectId;
                this.projectTitle = d.projectTitle || '';
                if (d.chapterNum != null) this.chapterNum = d.chapterNum;
                this.chapterTitle = d.chapterTitle || '';
                this.chapterMetas = d.chapterMetas || [];
                this.prevNum = d.prevNum;
                this.nextNum = d.nextNum;
                this.tabs = buildTabs(d.fieldAvail);

                // Select first available tab
                const first = this.tabs.find(t => t.available);
                if (first) await this.switchTab(first.field);
            } finally {
                this.booting = false;
            }
        },

        async switchTab(field) {
            if (this.activeTab === field) return;
            this.activeTab = field;

            // For contentDraft, also load content for comparison
            if (field === 'contentDraft' && !this.cache['content']) {
                await this.loadField('content');
            }

            if (!this.cache[field]) {
                await this.loadField(field);
            }
        },

        async loadField(field) {
            this.loading = true;
            try {
                const url = `/projects/${this.projectId}/inspect/chapters/${this.chapterNum}/field/${field}`;
                const resp = await fetch(url);
                const json = await resp.json();
                this.cache[field] = json;
            } catch (e) {
                this.cache[field] = { content: '加载失败: ' + e.message, type: 'text' };
            }
            this.loading = false;
        },

        renderCharacterStatesTable() {
            const raw = this.cache['characterStates']?.content;
            if (!raw) return '<p class="text-muted">无数据</p>';
            try {
                const states = JSON.parse(raw);
                if (!Array.isArray(states) || states.length === 0) {
                    return '<pre class="field-content">' + this.escapeHtml(raw) + '</pre>';
                }
                const keys = Object.keys(states[0]);
                let html = '<div class="table-responsive"><table class="table table-sm table-bordered">';
                html += '<thead class="table-light"><tr>';
                keys.forEach(k => { html += '<th>' + this.escapeHtml(k) + '</th>'; });
                html += '</tr></thead><tbody>';
                states.forEach(row => {
                    html += '<tr>';
                    keys.forEach(k => {
                        const val = row[k];
                        const display = typeof val === 'object' ? JSON.stringify(val) : String(val ?? '');
                        html += '<td style="font-size:0.85rem;white-space:pre-wrap;max-width:300px;">' + this.escapeHtml(display) + '</td>';
                    });
                    html += '</tr>';
                });
                html += '</tbody></table></div>';
                return html;
            } catch (e) {
                return '<pre class="field-content">' + this.escapeHtml(raw) + '</pre>';
            }
        },

        escapeHtml(str) {
            const div = document.createElement('div');
            div.textContent = str;
            return div.innerHTML;
        }
    };
}
