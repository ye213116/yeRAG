import { request } from '../request';

export type WorkspacePermission = 'READ' | 'WRITE' | 'EXECUTE';
export interface CodingRepository {
  id: string;
  name: string;
  branches: string[];
}
export interface CodingWorkspace {
  id: string;
  repositoryId: string;
  branch: string;
  baseSha: string;
  permissions: WorkspacePermission[];
  status: string;
  activeTaskId: string | null;
}
export interface CodingTaskSummary {
  id: string;
  workspace_id: string;
  status: string;
  summary?: string;
}
export interface CodingTask {
  id: string;
  workspaceId: string;
  status: string;
  summary: string | null;
  baseSha: string;
  publishState: string;
  prUrl: string | null;
  result: {
    diff?: string;
    changeSetHash?: string;
    verification?: { id?: string; status: string; tests?: number; failures?: number; errors?: number };
    cleanupPending?: boolean;
  } | null;
  events: Array<{ id: number; kind: string; preview: string }>;
}
export const fetchCodingCapabilities = () => request<{ enabled: boolean }>({ url: '/coding/capabilities' });
export const fetchCodingRepositories = () => request<CodingRepository[]>({ url: '/coding/repositories' });
export const fetchCodingWorkspaces = () => request<CodingWorkspace[]>({ url: '/coding/workspaces' });
export const createCodingWorkspace = (data: {
  repositoryId: string;
  branch: string;
  permissions: WorkspacePermission[];
}) => request<CodingWorkspace>({ url: '/coding/workspaces', method: 'post', data });
export const updateCodingPermissions = (id: string, permissions: WorkspacePermission[]) =>
  request({ url: `/coding/workspaces/${encodeURIComponent(id)}/permissions`, method: 'patch', data: { permissions } });
export const fetchCodingTasks = () => request<CodingTaskSummary[]>({ url: '/coding/tasks' });
export const fetchCodingTask = (id: string) => request<CodingTask>({ url: `/coding/tasks/${encodeURIComponent(id)}` });
export const cancelCodingTask = (id: string) =>
  request({ url: `/coding/tasks/${encodeURIComponent(id)}/cancel`, method: 'post' });
export const publishCodingTask = (
  id: string,
  data: { baseSha: string; changeSetHash: string; verificationId: string }
) => request<{ url: string }>({ url: `/coding/tasks/${encodeURIComponent(id)}/pull-request`, method: 'post', data });

export const fetchCodingEvent = (id: string, eventId: number) =>
  request<{ data_json: string }>({ url: `/coding/tasks/${encodeURIComponent(id)}/events/${eventId}` });
