function inspectChapter() {
    const data = window.__inspectData;
    const fieldAvail = data.fieldAvail;

    return {
        sidebarOpen: window.innerWidth > 768,
        activeTab: null,
        loading: false,
        cache: {},
        tabs: [
            { field: 'outlineSummary', label: '章节大纲', available: fieldAvail.outlineSummary },
            { field: 'writingBriefing', label: '写作简报', available: fieldAvail.writingBriefing },
            { field: 'eventPlan', label: '事件计划', available: fieldAvail.eventPlan },
            { field: 'content', label: '正文内容', available: fieldAvail.content },
            { field: 'contentDraft', label: '原稿对比', available: fieldAvail.contentDraft },
            { field: 'contentSummary', label: '内容摘要', available: fieldAvail.contentSummary },
            { field: 'characterStates', label: '角色状态', available: fieldAvail.characterStates },
        ],

        init() {
            // Select first available tab
            const first = this.tabs.find(t => t.available);
            if (first) this.switchTab(first.field);
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
                const url = `/projects/${data.projectId}/inspect/chapters/${data.chapterNum}/field/${field}`;
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
