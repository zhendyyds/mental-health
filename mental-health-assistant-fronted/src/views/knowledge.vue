<template>
    <div>
        <PageHead title="知识文章">
            <template #buttons>
                <el-button @click="handleEdit({})" type="primary">新增</el-button>
            </template>
        </PageHead>
        <TableSearch :formItem="formItem" @search="handleSearch" />
        <el-table :data="tableData" style="width: 100%;margin-top: 25px">
            <el-table-column width="450" label="文章标题" fixed="left">
                <template #default="scope">
                    <div style="display: flex; align-items: center">
                        <el-icon><timer /></el-icon>
                        <span>{{ scope.row.title }}</span>
                    </div>
                </template>
            </el-table-column>
             <el-table-column  label="分类" width="200">
                <template #default="scope">
                    <div style="display: flex; align-items: center">
                        <el-icon><timer /></el-icon>
                        <span>{{ categoryMap[scope.row.categoryId] }}</span>
                    </div>
                </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
                <template #default="scope">
                    <!-- 软删除 = 退出生命周期：已删除行只显示删除标记，不再展示生命周期标签 -->
                    <el-tag v-if="scope.row.deleted" type="danger" size="small">已删除</el-tag>
                    <el-tag v-else-if="scope.row.status === 0" type="info" size="small">草稿</el-tag>
                    <el-tag v-else-if="scope.row.status === 1" type="success" size="small">已发布</el-tag>
                    <el-tag v-else type="warning" size="small">已下线</el-tag>
                </template>
            </el-table-column>
            <el-table-column prop="authorName" label="作者" width="150" />
            <el-table-column prop="readCount" label="阅读量" width="150" />
            <el-table-column label="发布时间" width="150">
                <template #default="scope">
                    <span>{{ scope.row.publishedAt ? scope.row.publishedAt.slice(0, 10) : '未发布' }}</span>
                </template>
            </el-table-column>
            <el-table-column label="索引" width="130">
                <template #default="{ row }">
                    <el-button text type="primary" @click="showIndexJobs(row)">查看任务</el-button>
                </template>
            </el-table-column>
            <el-table-column  label="操作" width="240" fixed="right">
                <template #default="scope">
                    <el-button @click="handleEdit(scope.row)" text type="primary">编辑</el-button>
                    <el-button @click="handlePublish(scope.row)" v-if="scope.row.status === 0 || scope.row.status === 2" text type="success">发布</el-button>
                    <el-button @click="handleUnpublish(scope.row)" v-if="scope.row.status === 1" text type="warning">下线</el-button>
                    <el-button @click="handleDelete(scope.row)" text type="danger">删除</el-button>
                </template>
            </el-table-column>
        </el-table>
        <el-pagination
         style="margin-top: 25px"
         :page-size="pagination.size"
         layout="prev, pager, next"
         :total="pagination.total"
         @change="handleChange"
         />
         <ArticleDialog v-model:modelValue="dialogVisible" :article="currentArticle" :categories="categories" @success="handleSuccess" />
         <el-dialog v-model="indexDialogVisible" title="文章索引任务" width="min(720px, 94vw)">
             <p>{{ indexArticle?.title }}</p>
             <p v-if="indexLoadError" role="status">{{ indexLoadError }}</p>
             <el-table v-loading="indexLoading" :data="indexJobs" empty-text="暂无索引任务">
                 <el-table-column prop="id" label="任务 ID" width="92" />
                 <el-table-column prop="revision" label="版本" width="72" />
                 <el-table-column label="操作" width="82">
                     <template #default="{ row }">{{ row.operation === 'BUILD' ? '构建' : '清理' }}</template>
                 </el-table-column>
                 <el-table-column label="状态" min-width="105">
                     <template #default="{ row }">{{ indexStatusLabels[row.status] || row.status }}</template>
                 </el-table-column>
                 <el-table-column prop="errorCode" label="错误码" min-width="130" />
                 <el-table-column label="处理" width="95">
                     <template #default="{ row }">
                         <el-button v-if="row.status === 'FAILED' && row.retryable" type="primary" text
                             :disabled="indexLoading || retryingJobId !== null" @click="retryIndexJob(row)">重试</el-button>
                     </template>
                 </el-table-column>
             </el-table>
             <template #footer>
                 <el-button :disabled="indexLoading || retryingJobId !== null" @click="loadIndexJobs">刷新状态</el-button>
             </template>
         </el-dialog>
    </div>
</template>
<script setup>
import { onMounted, ref, reactive } from 'vue'
import PageHead from '@/components/PageHead.vue'
import TableSearch from '@/components/TableSearch.vue'
import { categoryTree, articlePage, getArticleDetail, changeArticleStatus, deleteArticle, getArticleIndexJobs, retryArticleIndexJob } from '@/api/admin'
import ArticleDialog from '@/components/ArticleDialog.vue'
import { ElMessageBox, ElMessage } from 'element-plus'


const formItem = [
    { comp: 'input', prop: 'title', label: '文章标题', placeholder: '请输入文章标题' },
    { comp: 'select', prop: 'categoryId', label: '分类',placeholder: '请选择分类' },
    { comp: 'select', prop: 'status', label: '状态', placeholder: '请输入文章内容', options: [{
       label: '草稿',
       value: '0'
    },{
       label: '已发布',
       value: '1'
    },{
       label: '已下线',
       value: '2'
    }] }
]

// 分页参数
const pagination = reactive({
    currentPage: 1,
    size: 10,
    total: 0
})

const handleSearch = async (formData) => {
    console.log(formData, '查询参数')

    const params = {
        ...pagination,
        ...formData
    }

    const { records, total } = await articlePage(params)
    tableData.value = records
    pagination.total = total
}

const handleChange = (page) => {
    pagination.currentPage = page
    handleSearch()
}

// 分类映射
const categoryMap = reactive({})
// 分类列表
const categories = ref([])

// 列表数据
const tableData = ref([])

const indexStatusLabels = {
    PENDING: '排队中', RUNNING: '执行中', RETRY_WAIT: '等待重试', SUCCEEDED: '已完成', FAILED: '失败',
    CANCELLED: '已取消', SUPERSEDED: '已被替换'
}
const indexDialogVisible = ref(false)
const indexArticle = ref(null)
const indexJobs = ref([])
const indexLoading = ref(false)
const indexLoadError = ref('')
const retryingJobId = ref(null)
let indexLoadSequence = 0

const loadIndexJobs = async () => {
    if (!indexArticle.value) return
    const sequence = ++indexLoadSequence
    indexLoading.value = true
    indexLoadError.value = ''
    try {
        const jobs = await getArticleIndexJobs(indexArticle.value.id)
        if (sequence === indexLoadSequence) indexJobs.value = jobs
    } catch (error) {
        if (sequence === indexLoadSequence) indexLoadError.value = error?.message || '索引任务暂时无法加载'
    } finally {
        if (sequence === indexLoadSequence) indexLoading.value = false
    }
}

const showIndexJobs = (article) => {
    indexArticle.value = article
    indexJobs.value = []
    indexDialogVisible.value = true
    loadIndexJobs()
}

const retryIndexJob = async (job) => {
    if (indexLoading.value || retryingJobId.value !== null) return
    retryingJobId.value = job.id
    try {
        await retryArticleIndexJob(job.id)
        ElMessage.success('已重新排队')
        if (indexDialogVisible.value && indexArticle.value?.id === job.articleId) await loadIndexJobs()
    } catch (error) {
        ElMessage.error(error?.message || '索引任务重试失败')
    } finally {
        retryingJobId.value = null
    }
}

// 新增和编辑
const dialogVisible = ref(false)
const currentArticle = ref(null)
const handleSuccess = () => {
    dialogVisible.value = false
    // 刷新列表
    handleSearch()
}
const handleEdit = (row) => {
    if (!row.id) {
        // 新增
        currentArticle.value = null
        dialogVisible.value = true

    } else {
        // 编辑
        getArticleDetail(row.id).then(res => {
            console.log(res, '编辑详情')
            currentArticle.value = res
            dialogVisible.value = true
        })
    }
    
}

// 发布
const handlePublish = (row) => {
    ElMessageBox.confirm(
        `确认发布文章${row.title}吗？`,
        '确认',
        {
            confirmButtonText: '确认发布',
            cancelButtonText: '取消',
            type: 'info'
        }
    ).then(() => {
        changeArticleStatus(row.id, { status: 1 }).then(res => {
            ElMessage.success('发布成功')
            handleSearch()
        }).catch(error => ElMessage.error(error?.message || '发布失败，请稍后重试'))
    }).catch(() => {})
}

const handleUnpublish = (row) => {
    ElMessageBox.confirm(
        `确认下线文章${row.title}吗？`,
        '确认',
        {
            confirmButtonText: '确认下线',
            cancelButtonText: '取消',
            type: 'warning'
        }
    ).then(() => {
        changeArticleStatus(row.id, { status: 2 }).then(res => {
            ElMessage.success('下线成功')
            handleSearch()
        }).catch(error => ElMessage.error(error?.message || '下线失败，请稍后重试'))
    }).catch(() => {})
}

const handleDelete = (row) => {
    ElMessageBox.confirm(
        `确认删除文章${row.title}吗？`,
        '确认',
        {
            confirmButtonText: '确认删除',
            cancelButtonText: '取消',
            type: 'danger'
        }
    ).then(() => {
        deleteArticle(row.id).then(res => {
            ElMessage.success('删除成功')
            handleSearch()
        }).catch(error => ElMessage.error(error?.message || '删除失败，请稍后重试'))
    }).catch(() => {})
}

onMounted(async () => {
    const data = await categoryTree()
    
    categories.value = data.map(item => {
        categoryMap[item.id] = item.categoryName
        return {
            label: item.categoryName,
            value: item.id
        }
    })
    console.log(categories.value)
    formItem[1].options = categories.value

    // 获取列表
    handleSearch()
})
</script>
