import type { RouteRecordRaw } from 'vue-router'

/** 默认布局 */
const Layout = () => import('@/layout/index.vue')

/** 系统路由 */
export const systemRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { hidden: true },
  },
  {
    path: '/',
    name: 'Dashboard',
    component: Layout,
    // 登录后默认落在工单列表，而不是框架自带的工作台。
    // 工作台那页展示的是 ContiNew 自己的项目介绍和广告，对这个项目来说是噪音。
    redirect: '/ticket/list',
    meta: { title: '仪表盘', icon: 'dashboard', hidden: true },
    children: [
      {
        path: '/dashboard/workplace',
        name: 'Workplace',
        component: () => import('@/views/dashboard/workplace/index.vue'),
        meta: { title: '工作台', icon: 'desktop', hidden: true },
      },
      {
        path: '/dashboard/analysis',
        name: 'Analysis',
        component: () => import('@/views/dashboard/analysis/index.vue'),
        meta: { title: '分析页', icon: 'insert-chart', hidden: true },
      },
    ],
  },
  {
    path: '/social/callback',
    component: () => import('@/views/login/social/index.vue'),
    meta: { hidden: true },
  },
  {
    path: '/pwdExpired',
    component: () => import('@/views/login/pwdExpired/index.vue'),
    meta: { hidden: true },
  },
  {
    path: '/user',
    name: 'User',
    component: Layout,
    meta: { hidden: true },
    children: [
      {
        path: '/user/profile',
        name: 'UserProfile',
        component: () => import('@/views/user/profile/index.vue'),
        meta: { title: '个人中心', showInTabs: false },
      },
      {
        path: '/user/message',
        name: 'UserMessage',
        component: () => import('@/views/user/message/index.vue'),
        meta: { title: '消息中心', showInTabs: false },
      },
      {
        path: '/user/notice',
        name: 'UserNotice',
        component: () => import('@/views/user/message/components/view/index.vue'),
        meta: { title: '查看公告' },
      },
    ],
  },
  {
    path: '/about',
    name: 'About',
    component: Layout,
    meta: { title: '关于项目', icon: 'apps', hidden: false, sort: 999 },
    redirect: '/about/document/api',
    children: [
      {
        path: '/about/document/api',
        component: () => import('@/views/about/document/api/index.vue'),
        meta: { title: '接口文档', icon: 'swagger', hidden: false, keepAlive: true },
      },
      {
        path: '/about/document/changelog',
        component: () => import('@/views/about/document/changelog/index.vue'),
        meta: { title: '更新日志', icon: 'continew', hidden: false, keepAlive: true },
      },
      {
        path: 'https://arco.design/vue/component/button',
        meta: { title: 'Arco Design文档', icon: 'arco', hidden: false },
      },
      {
        path: '/about/source',
        name: 'AboutSource',
        meta: { title: '开源地址', icon: 'github', hidden: false },
        children: [
          {
            path: 'https://gitee.com/continew/continew-admin',
            meta: { title: 'Gitee', icon: 'gitee', hidden: false },
          },
          {
            path: 'https://atomgit.com/continew/continew-admin',
            meta: { title: 'GitCode', icon: 'gitcode', hidden: false },
          },
          {
            path: 'https://github.com/continew-org/continew-admin',
            meta: { title: 'GitHub', icon: 'github', hidden: false },
          },
        ],
      },
    ],
  },
]

// 固定路由（默认路由）
export const constantRoutes: RouteRecordRaw[] = [
  {
    path: '/redirect',
    component: Layout,
    meta: { hidden: true },
    children: [
      {
        path: '/redirect/:path(.*)',
        component: () => import('@/views/default/redirect/index.vue'),
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    component: () => import('@/views/default/error/404.vue'),
    meta: { hidden: true },
  },
  {
    path: '/403',
    component: () => import('@/views/default/error/403.vue'),
    meta: { hidden: true },
  },
]
