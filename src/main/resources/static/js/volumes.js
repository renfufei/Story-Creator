/**
 * 分卷管理：手工调整「章节 ↔ 分卷」的归属关系。
 *
 * 数据流：
 *   GET  /api/projects/{id}/volumes            —— 引导数据（分卷 + 实际章节号 + 每卷章节数 + 开关状态）
 *   POST /api/projects/{id}/volumes/rebuild    —— 按「每卷 N 章」重建（全量重算绑定）
 *   POST /api/projects/{id}/volumes/{vid}/chapters —— 把若干章节移入该卷
 *   POST /api/projects/{id}/volumes            —— 末尾新增空分卷
 *   PUT  /api/projects/{id}/volumes/{vid}      —— 改名
 *   DELETE /api/projects/{id}/volumes/{vid}    —— 删除（章节先迁到相邻卷）
 *
 * 兼容语义：项目未开启绑定开关时，后端按 projects.chaptersPerVolume 推算；
 * 任何一次手工调整都会开启开关，之后以显式绑定为准（未绑定的章节仍按原公式兜底）。
 */
function volumeManagerApp() {
    return {
        projectId: 0,
        projectTitle: '',
        chaptersPerVolume: 10,
        bindingEnabled: false,
        volumes: [],
        chapters: [],
        unboundChapterNumbers: [],
        selectedChapters: [],
        moveTargetVolumeId: null,
        adjustCount: 5,
        rebuildCount: 10,
        busy: false,
        message: '',
        error: '',

        // 注意：不要写 x-init="init()" —— Alpine 会自动调用 init()，重复声明会导致初始化两次。
        init() {
            const m = location.pathname.match(/\/projects\/(\d+)/);
            this.projectId = m ? parseInt(m[1], 10) : 0;
            this.load();
        },

        get base() {
            return '/api/projects/' + this.projectId + '/volumes';
        },

        async load() {
            this.error = '';
            try {
                const d = await SC.api.get(this.base);
                this.projectTitle = d.projectTitle || '';
                this.chaptersPerVolume = d.chaptersPerVolume || 10;
                this.bindingEnabled = !!d.bindingEnabled;
                this.volumes = d.volumes || [];
                this.chapters = d.chapters || [];
                this.unboundChapterNumbers = d.unboundChapterNumbers || [];
                this.selectedChapters = [];
                if (!this.moveTargetVolumeId && this.volumes.length) {
                    this.moveTargetVolumeId = this.volumes[0].id;
                }
                if (!this.rebuildCount) this.rebuildCount = this.chaptersPerVolume;
            } catch (e) {
                this.error = e.message || '加载失败';
            }
        },

        chapterTitle(num) {
            const c = this.chapters.find(x => x.number === num);
            return c ? (c.title || '') : '';
        },

        totalChapters() {
            return this.chapters.length;
        },

        boundCount() {
            let n = 0;
            this.volumes.forEach(v => n += (v.chapterNumbers || []).length);
            return n;
        },

        volumeLabel(v) {
            return '第' + v.volumeNumber + '卷' + (v.title ? ' ' + v.title : '');
        },

        // ------------------------- 选择 & 移动 -------------------------

        toggleSelect(num) {
            const i = this.selectedChapters.indexOf(num);
            if (i >= 0) this.selectedChapters.splice(i, 1);
            else this.selectedChapters.push(num);
        },

        selectAllInVolume(v) {
            (v.chapterNumbers || []).forEach(n => {
                if (this.selectedChapters.indexOf(n) < 0) this.selectedChapters.push(n);
            });
        },

        clearSelection() {
            this.selectedChapters = [];
        },

        async moveSelected() {
            if (!this.selectedChapters.length || !this.moveTargetVolumeId) return;
            await this.moveChapters(this.selectedChapters.slice(), this.moveTargetVolumeId);
            this.selectedChapters = [];
        },

        async moveChapters(numbers, volumeId) {
            this.busy = true;
            this.error = '';
            this.message = '';
            try {
                await SC.api.post(this.base + '/' + volumeId + '/chapters', { chapterNumbers: numbers });
                this.message = '已把 ' + numbers.length + ' 章移动到「' + this.volumeLabel(this.volumeById(volumeId)) + '」';
                await this.load();
            } catch (e) {
                this.error = e.message || '移动失败';
            } finally {
                this.busy = false;
            }
        },

        volumeById(id) {
            return this.volumes.find(v => v.id === id) || { volumeNumber: 0, title: '' };
        },

        nextVolume(index) {
            return index >= 0 && index < this.volumes.length - 1 ? this.volumes[index + 1] : null;
        },

        /** 从下一卷取前 N 章并入本卷 —— 对应「某个分卷再增加几章」。 */
        async takeFromNext(index, count) {
            const next = this.nextVolume(index);
            if (!next) return;
            const numbers = (next.chapterNumbers || []).slice(0, Math.max(1, count | 0));
            if (!numbers.length) {
                this.error = '下一卷没有可移动的章节';
                return;
            }
            await this.moveChapters(numbers, this.volumes[index].id);
        },

        /** 把本卷末尾 N 章还给下一卷 —— 对应「某个分卷减少几章」。 */
        async giveToNext(index, count) {
            const next = this.nextVolume(index);
            if (!next) return;
            const own = this.volumes[index].chapterNumbers || [];
            const numbers = own.slice(Math.max(0, own.length - Math.max(1, count | 0)));
            if (!numbers.length) {
                this.error = '本卷没有可移出的章节';
                return;
            }
            await this.moveChapters(numbers, next.id);
        },

        // ------------------------- 分卷增删 -------------------------

        async rebuild() {
            if (!(await this.confirm('重建会按「每卷 ' + (this.rebuildCount || '?') + ' 章」重算所有分卷与章节归属，已手工调整的结果将被覆盖，确定继续？'))) return;
            this.busy = true;
            this.error = '';
            this.message = '';
            try {
                await SC.api.post(this.base + '/rebuild', { chaptersPerVolume: this.rebuildCount });
                this.message = '已按每卷 ' + this.rebuildCount + ' 章重建分卷绑定';
                await this.load();
            } catch (e) {
                this.error = e.message || '重建失败';
            } finally {
                this.busy = false;
            }
        },

        async addVolume() {
            this.busy = true;
            this.error = '';
            try {
                await SC.api.post(this.base, {});
                this.message = '已在末尾新增一个空分卷，可把其它卷的章节移进来';
                await this.load();
            } catch (e) {
                this.error = e.message || '新增失败';
            } finally {
                this.busy = false;
            }
        },

        async removeVolume(v) {
            if (!(await this.confirm('删除「' + this.volumeLabel(v) + '」？其名下 ' + (v.chapterNumbers || []).length + ' 章会先迁移到相邻分卷。'))) return;
            this.busy = true;
            this.error = '';
            try {
                await SC.api.del(this.base + '/' + v.id);
                this.message = '已删除「' + this.volumeLabel(v) + '」';
                await this.load();
            } catch (e) {
                this.error = e.message || '删除失败';
            } finally {
                this.busy = false;
            }
        },

        async renameVolume(v) {
            const input = window.prompt('分卷标题', v.title || ('第' + v.volumeNumber + '卷'));
            if (input === null) return;
            this.busy = true;
            this.error = '';
            try {
                await SC.api.put(this.base + '/' + v.id, { title: input });
                await this.load();
            } catch (e) {
                this.error = e.message || '改名失败';
            } finally {
                this.busy = false;
            }
        },

        confirm(message) {
            if (typeof SC !== 'undefined' && SC.confirm) return SC.confirm({ message: message, confirmText: '确定' });
            return Promise.resolve(window.confirm(message));
        }
    };
}
