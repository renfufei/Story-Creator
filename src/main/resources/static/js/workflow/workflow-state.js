function workflowStateMixin() {
    return {
        wfStateModal: null,
        wfStateCurrentStep: '',
        wfStateSteps: {},
        wfStateAllowedStatuses: {},
        characterStateDims: [],

        openWorkflowStateModal() {
            fetch(`/projects/${this.projectId}/workflow-states`)
                .then(r => r.json())
                .then(data => {
                    this.wfStateCurrentStep = data.currentStep;
                    this.wfStateSteps = data.stepStatuses;
                    this.wfStateAllowedStatuses = data.allowedStatuses || {};
                    if (!this.wfStateModal) {
                        this.wfStateModal = new bootstrap.Modal(document.getElementById('workflowStateModal'));
                    }
                    this.wfStateModal.show();
                })
                .catch(err => alert('加载工作流状态失败: ' + err.message));
        },

        saveWorkflowState() {
            const payload = {
                currentStep: this.wfStateCurrentStep,
                stepStatuses: this.wfStateSteps
            };
            fetch(`/projects/${this.projectId}/workflow-states`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            })
            .then(r => r.json())
            .then(data => {
                if (data.status === 'ok') {
                    if (this.wfStateModal) this.wfStateModal.hide();
                    location.reload();
                } else {
                    alert('保存失败: ' + (data.message || ''));
                }
            })
            .catch(err => alert('保存失败: ' + err.message));
        },

        toggleDimConfig(dimKey, enabled) {
            const dim = this.characterStateDims.find(d => d.key === dimKey);
            if (dim) dim.enabled = enabled;
            fetch(`/projects/${this.projectId}/character-state-dims`, {
                method: 'PUT',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({dimKey, enabled})
            }).catch(() => { if (dim) dim.enabled = !enabled; });
        }
    };
}
