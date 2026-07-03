function materialsMixin() {
    return {
        // Material reference state
        materialLibraryItems: [],
        materialLibraryLoading: false,
        selectedMaterialIds: [],
        materialFilterCategory: '',

        // Distill state
        distillSourceType: 'world',
        distillCategory: 'WORLD',
        distillName: '',
        distillConfigId: '',
        distillLoading: false,
        distillResult: null,
        distillError: '',
        distillNeedsSecondary: false,
        distillSecondaryLabel: '',
        distillSecondaryOptions: [],
        distillSecondaryValue: '',

        loadMaterialLibrary() {
            this.materialLibraryLoading = true;
            let url = '/settings/materials/list-json';
            if (this.materialFilterCategory) {
                url += '?category=' + this.materialFilterCategory;
            }
            fetch(url)
                .then(checkResponse)
                .then(data => {
                    this.materialLibraryItems = data;
                    this.materialLibraryLoading = false;
                })
                .catch(() => {
                    this.materialLibraryItems = [];
                    this.materialLibraryLoading = false;
                });
        },

        openMaterialRefModal() {
            this.loadMaterialLibrary();
            new bootstrap.Modal(document.getElementById('materialRefModal')).show();
        },

        toggleMaterial(id) {
            const idx = this.selectedMaterialIds.indexOf(id);
            if (idx >= 0) {
                this.selectedMaterialIds.splice(idx, 1);
            } else {
                this.selectedMaterialIds.push(id);
            }
        },

        isMaterialSelected(id) {
            return this.selectedMaterialIds.includes(id);
        },

        clearMaterialSelection() {
            this.selectedMaterialIds = [];
        },

        getMaterialIdsParam() {
            if (this.selectedMaterialIds.length === 0) return '';
            return this.selectedMaterialIds.map(id => 'materialIds=' + id).join('&');
        },

        // Distill methods
        openDistillModal() {
            this.distillResult = null;
            this.distillError = '';
            this.distillName = '';
            this.distillSourceType = 'world';
            this.distillCategory = 'WORLD';
            this.distillConfigId = '';
            this.distillNeedsSecondary = false;
            this.distillSecondaryLabel = '';
            this.distillSecondaryOptions = [];
            this.distillSecondaryValue = '';
            new bootstrap.Modal(document.getElementById('distillMaterialModal')).show();
        },

        onDistillSourceChange() {
            this.distillSecondaryValue = '';
            this.distillNeedsSecondary = false;
            this.distillSecondaryOptions = [];
            if (this.distillSourceType === 'character') {
                this.distillNeedsSecondary = true;
                this.distillSecondaryLabel = '选择角色';
                this.distillSecondaryOptions = (this.characterList || []).map(c => ({
                    value: 'character:' + c.id, label: c.name
                }));
            } else if (this.distillSourceType === 'chapter_outline') {
                this.distillNeedsSecondary = true;
                this.distillSecondaryLabel = '选择章节大纲';
                // Build from outlineVolumes if available
                const options = [];
                if (this.outlineVolumes) {
                    for (const vol of this.outlineVolumes) {
                        for (const ch of (vol.chapters || [])) {
                            options.push({ value: 'chapter_outline:' + ch.chapterNumber, label: ch.title || ('第' + ch.chapterNumber + '章') });
                        }
                    }
                }
                this.distillSecondaryOptions = options;
            } else if (this.distillSourceType === 'chapter') {
                this.distillNeedsSecondary = true;
                this.distillSecondaryLabel = '选择章节';
                this.distillSecondaryOptions = (this.chapterListData || [])
                    .filter(ch => ch.content || ch.status === 'COMPLETED' || ch.status === 'GENERATED')
                    .map(ch => ({
                        value: 'chapter:' + ch.chapterNumber, label: ch.title || ('第' + ch.chapterNumber + '章')
                    }));
            }
        },

        get canExecuteDistill() {
            if (!this.distillSourceType) return false;
            if (this.distillNeedsSecondary && !this.distillSecondaryValue) return false;
            return true;
        },

        executeDistill() {
            this.distillLoading = true;
            this.distillResult = null;
            this.distillError = '';

            const actualSource = this.distillNeedsSecondary ? this.distillSecondaryValue : this.distillSourceType;
            const formData = new FormData();
            formData.append('projectId', this.projectId);
            formData.append('sourceType', actualSource);
            formData.append('category', this.distillCategory);
            if (this.distillName) formData.append('name', this.distillName);
            if (this.distillConfigId) formData.append('configId', this.distillConfigId);

            fetch('/settings/materials/distill', { method: 'POST', body: formData })
                .then(r => r.json())
                .then(data => {
                    this.distillLoading = false;
                    if (data.error) {
                        this.distillError = data.error;
                    } else {
                        this.distillResult = data;
                    }
                })
                .catch(err => {
                    this.distillLoading = false;
                    this.distillError = '请求失败: ' + err;
                });
        }
    };
}
