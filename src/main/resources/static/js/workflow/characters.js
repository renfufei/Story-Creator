function workflowCharactersMixin() {
    return {
        characterList: [],
        editingCharacterId: null,
        editForm: {},
        showAddCharacter: false,
        newCharacterName: '',
        refiningCharacters: false,
        refineProgress: '',
        characterOverview: '',
        showOverview: true,
        refineStreamContent: '',

        setupCharacterStreamEvents(eventSource) {
            eventSource.addEventListener('char-section', (e) => {
                this.loadCharacterList();
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
                this.loadCharacterList();
            });

            eventSource.addEventListener('stopped', () => {
                this.generating = false;
                this.currentEventSource = null;
                this.stopElapsedTimer();
                eventSource.close();
                this.loadCharacterList();
            });

            eventSource.addEventListener('error', (e) => {
                this.generating = false;
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

        loadCharacterList() {
            if (this.currentStep !== 'CHARACTER_DESIGN') return;
            fetch(`/projects/${this.projectId}/characters`)
                .then(r => r.json())
                .then(data => { this.characterList = data; })
                .catch(err => console.error('Failed to load characters:', err));
            this.loadCharacterOverview();
        },

        loadCharacterOverview() {
            fetch(`/projects/${this.projectId}/characters/overview`)
                .then(r => r.json())
                .then(data => { this.characterOverview = data.content || ''; })
                .catch(err => console.error('Failed to load character overview:', err));
        },

        editCharacter(ch) {
            this.editingCharacterId = ch.id;
            this.editForm = { ...ch };
        },

        saveCharacter() {
            const formData = new FormData();
            const fields = ['name', 'gender', 'age', 'role', 'personality', 'appearance', 'background', 'motivation', 'abilities', 'relationships', 'description'];
            fields.forEach(f => { if (this.editForm[f] != null) formData.append(f, this.editForm[f]); });
            fetch(`/projects/${this.projectId}/characters/${this.editingCharacterId}`, {
                method: 'POST',
                body: formData
            })
            .then(r => r.json())
            .then(() => {
                this.editingCharacterId = null;
                this.loadCharacterList();
            })
            .catch(err => alert('保存失败: ' + err));
        },

        addCharacter() {
            if (!this.newCharacterName.trim()) { alert('请输入角色姓名'); return; }
            const formData = new FormData();
            formData.append('name', this.newCharacterName.trim());
            fetch(`/projects/${this.projectId}/characters/add`, {
                method: 'POST',
                body: formData
            })
            .then(r => r.json())
            .then(() => {
                this.newCharacterName = '';
                this.showAddCharacter = false;
                this.loadCharacterList();
            })
            .catch(err => alert('添加失败: ' + err));
        },

        deleteCharacter(id) {
            if (!confirm('确定删除该角色？')) return;
            fetch(`/projects/${this.projectId}/characters/${id}/delete`, { method: 'POST' })
                .then(r => r.json())
                .then(() => this.loadCharacterList())
                .catch(err => alert('删除失败: ' + err));
        },

        refineAllCharacters() {
            if (this.refiningCharacters) return;
            this.refiningCharacters = true;
            this.refineProgress = '正在准备精修...';

            const es = new EventSource(`/projects/${this.projectId}/characters/refine-all`);
            let currentIdx = 0;

            es.addEventListener('char-refine', (e) => {
                const data = e.data;
                const match = data.match(/\[\[CHAR:REFINE:(\d+)\]\]/);
                if (match) {
                    currentIdx = parseInt(match[1]);
                    this.refineProgress = `正在精修第 ${currentIdx} 个角色...`;
                    this.refineStreamContent = '';
                    const unrefined = this.characterList.filter(c => c.status !== 'REFINED');
                    if (currentIdx - 1 < unrefined.length) {
                        const target = this.characterList.find(c => c.id === unrefined[currentIdx - 1].id);
                        if (target) target.status = 'REFINING';
                    }
                }
                if (data.includes('DONE')) {
                    if (currentIdx === 0) {
                        this.refineProgress = '所有角色已精修，无需重复执行';
                    } else {
                        this.refineProgress = '精修完成！';
                    }
                }
            });

            es.addEventListener('token', (e) => {
                this.refineStreamContent += e.data;
                if (this.$refs.refineStreamOutput) {
                    this.$refs.refineStreamOutput.scrollTop = this.$refs.refineStreamOutput.scrollHeight;
                }
            });

            es.addEventListener('done', (e) => {
                es.close();
                this.refiningCharacters = false;
                this.refineProgress = '';
                this.refineStreamContent = '';
                this.loadCharacterList();
            });

            es.addEventListener('error', (e) => {
                es.close();
                this.refiningCharacters = false;
                this.refineProgress = '';
                this.refineStreamContent = '';
                alert('精修失败: ' + (e.data || '连接中断'));
                this.loadCharacterList();
            });

            es.onerror = () => {
                es.close();
                this.refiningCharacters = false;
                this.refineProgress = '';
                this.refineStreamContent = '';
                this.loadCharacterList();
            };
        }
    };
}
