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
        editingOverview: false,
        editOverviewContent: '',
        refineStreamContent: '',
        // Image generation state
        imageModalCharId: null,
        imageModalCharName: '',
        characterImages: [],
        imageLoading: false,
        imageConfigs: [],
        selectedImageConfigId: null,
        textConfigs: [],
        selectedTextConfigId: null,
        selectedImageType: 'AVATAR',
        imageWidth: 1024,
        imageHeight: 1024,
        autoGenSteps: { prompt: true, image: true },
        promptGenerating: {},
        imgGenerating: {},
        editingPrompts: {},
        autoGenerating: false,

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

        saveCharacterOverview() {
            const formData = new FormData();
            formData.append('content', this.editOverviewContent);
            fetch(`/projects/${this.projectId}/characters/overview`, {
                method: 'POST',
                body: formData
            })
            .then(r => r.json())
            .then(() => {
                this.characterOverview = this.editOverviewContent;
                this.editingOverview = false;
            })
            .catch(err => alert('保存失败: ' + err));
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
        },

        // ===== Image Modal =====

        openImageModal(ch) {
            this.imageModalCharId = ch.id;
            this.imageModalCharName = ch.name;
            this.characterImages = [];
            this.autoGenerating = false;
            this.promptGenerating = {};
            this.imgGenerating = {};
            this.editingPrompts = {};
            this.imageWidth = 1024;
            this.imageHeight = 1024;
            this.selectedImageType = 'AVATAR';
            this.loadCharacterImages(ch.id);
            this.loadImageConfigs();
            this.loadTextConfigs();
            new bootstrap.Modal(document.getElementById('characterImageModal')).show();
        },

        setResolution(w, h) {
            this.imageWidth = w;
            this.imageHeight = h;
        },

        onImageTypeChange() {
            if (this.selectedImageType === 'AVATAR') {
                this.imageWidth = 1024;
                this.imageHeight = 1024;
            } else if (this.selectedImageType === 'PORTRAIT') {
                this.imageWidth = 1024;
                this.imageHeight = 1792;
            }
        },

        loadCharacterImages(charId) {
            this.imageLoading = true;
            fetch(`/projects/${this.projectId}/characters/${charId}/images`)
                .then(r => r.json())
                .then(data => {
                    this.characterImages = data;
                    // Initialize editing prompts
                    data.forEach(img => {
                        if (!this.editingPrompts[img.id]) {
                            this.editingPrompts[img.id] = img.imagePrompt || '';
                        }
                    });
                    this.imageLoading = false;
                })
                .catch(err => { console.error('Failed to load images:', err); this.imageLoading = false; });
        },

        loadImageConfigs() {
            fetch(`/projects/${this.projectId}/image-configs`)
                .then(r => r.json())
                .then(data => {
                    this.imageConfigs = data;
                    const def = data.find(c => c.isDefault);
                    this.selectedImageConfigId = def ? def.id : (data.length > 0 ? data[0].id : null);
                })
                .catch(err => console.error('Failed to load image configs:', err));
        },

        loadTextConfigs() {
            fetch(`/projects/${this.projectId}/text-configs`)
                .then(r => r.json())
                .then(data => {
                    this.textConfigs = data;
                    if (data.length > 0 && !this.selectedTextConfigId) {
                        this.selectedTextConfigId = data[0].id;
                    }
                })
                .catch(err => console.error('Failed to load text configs:', err));
        },

        async createAndAutoGenerate() {
            if (this.autoGenerating || !this.imageModalCharId) return;
            if (!this.autoGenSteps.prompt && !this.autoGenSteps.image) {
                alert('请至少勾选一个步骤');
                return;
            }
            if (this.autoGenSteps.image && !this.selectedImageConfigId) {
                alert('请先选择图像模型或在设置页面添加 IMAGE 类型模型');
                return;
            }

            this.autoGenerating = true;
            try {
                // Step 0: Create record
                const params = new URLSearchParams();
                params.append('imageType', this.selectedImageType);
                if (this.selectedImageConfigId) params.append('imageConfigId', this.selectedImageConfigId);
                if (this.selectedTextConfigId) params.append('textConfigId', this.selectedTextConfigId);

                const createRes = await fetch(`/projects/${this.projectId}/characters/${this.imageModalCharId}/images/create`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: params.toString()
                }).then(r => r.json());

                if (!createRes.success) {
                    alert(createRes.message || '创建失败');
                    this.autoGenerating = false;
                    return;
                }

                const imageId = createRes.id;
                this.characterImages.unshift(createRes);
                this.editingPrompts[imageId] = createRes.imagePrompt || '';

                // Step 1: Generate prompt (always for PORTRAIT, or if not pre-filled for AVATAR)
                if (this.autoGenSteps.prompt && (this.selectedImageType === 'PORTRAIT' || createRes.status === 'PROMPT_PENDING')) {
                    await this.generatePromptForCard(imageId);
                }

                // Step 2: Generate image (if checked)
                if (this.autoGenSteps.image) {
                    const currentImg = this.characterImages.find(i => i.id === imageId);
                    if (currentImg && (currentImg.status === 'PROMPT_READY' || currentImg.imagePrompt)) {
                        await this.generateImageForCard(imageId);
                    }
                }
            } catch (err) {
                console.error('Auto generate failed:', err);
                alert('自动生成失败: ' + err);
            } finally {
                this.autoGenerating = false;
            }
        },

        async generatePromptForCard(imageId) {
            this.promptGenerating[imageId] = true;
            try {
                const params = new URLSearchParams();
                if (this.selectedTextConfigId) params.append('textConfigId', this.selectedTextConfigId);

                const res = await fetch(`/projects/${this.projectId}/characters/${this.imageModalCharId}/images/${imageId}/generate-prompt`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: params.toString()
                }).then(r => r.json());

                if (res.success) {
                    const idx = this.characterImages.findIndex(i => i.id === imageId);
                    if (idx >= 0) {
                        this.characterImages[idx] = { ...this.characterImages[idx], ...res };
                    }
                    this.editingPrompts[imageId] = res.imagePrompt || '';
                } else {
                    alert(res.message || '生成提示词失败');
                }
            } catch (err) {
                alert('生成提示词失败: ' + err);
            } finally {
                this.promptGenerating[imageId] = false;
            }
        },

        async generateImageForCard(imageId) {
            this.imgGenerating[imageId] = true;
            try {
                const params = new URLSearchParams();
                const prompt = this.editingPrompts[imageId];
                if (prompt) params.append('prompt', prompt);
                if (this.selectedImageConfigId) params.append('imageConfigId', this.selectedImageConfigId);
                if (this.imageWidth) params.append('width', this.imageWidth);
                if (this.imageHeight) params.append('height', this.imageHeight);

                const res = await fetch(`/projects/${this.projectId}/characters/${this.imageModalCharId}/images/${imageId}/generate-image`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: params.toString()
                }).then(r => r.json());

                if (res.success) {
                    const idx = this.characterImages.findIndex(i => i.id === imageId);
                    if (idx >= 0) {
                        this.characterImages[idx] = { ...this.characterImages[idx], ...res };
                    }
                } else {
                    alert(res.message || '生成图片失败');
                }
            } catch (err) {
                alert('生成图片失败: ' + err);
            } finally {
                this.imgGenerating[imageId] = false;
            }
        },

        async savePrompt(imageId) {
            const prompt = this.editingPrompts[imageId];
            if (!prompt) return;
            const params = new URLSearchParams();
            params.append('prompt', prompt);
            try {
                const res = await fetch(`/projects/${this.projectId}/characters/${this.imageModalCharId}/images/${imageId}/update-prompt`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: params.toString()
                }).then(r => r.json());
                if (res.success) {
                    const idx = this.characterImages.findIndex(i => i.id === imageId);
                    if (idx >= 0) {
                        this.characterImages[idx] = { ...this.characterImages[idx], ...res };
                    }
                } else {
                    alert(res.message || '保存失败');
                }
            } catch (err) {
                alert('保存失败: ' + err);
            }
        },

        deleteImage(imageId) {
            if (!confirm('确定删除这张图片？')) return;
            fetch(`/projects/${this.projectId}/images/${imageId}/delete`, { method: 'POST' })
                .then(r => r.json())
                .then(data => {
                    if (data.success) {
                        this.characterImages = this.characterImages.filter(img => img.id !== imageId);
                        delete this.editingPrompts[imageId];
                    } else {
                        alert(data.message || '删除失败');
                    }
                })
                .catch(err => alert('删除失败: ' + err));
        }
    };
}
