import { request } from '../request';

export interface ArtifactSource {
  number: number;
  kind: 'knowledge' | 'web';
  title: string;
  fileMd5?: string;
  chunkId?: number;
  url?: string;
  fetchedAt?: string;
  evidence?: string;
}

export interface ArtifactSummary {
  id: string;
  version: number;
  title: string;
  sourceCount: number;
  updatedAt: string;
}

export interface ArtifactVersion {
  id: string;
  version: number;
  title: string;
  content: string;
  sources: ArtifactSource[];
  createdAt: string;
}

export interface ArtifactSaveRequest {
  title: string;
  content: string;
  expectedVersion: number;
  requestId: string;
}

export function fetchArtifacts(offset = 0) {
  return request<{ items: ArtifactSummary[]; nextOffset: number | null }>({ url: '/artifacts', params: { offset } });
}

export function fetchArtifact(id: string, version?: number) {
  return request<ArtifactVersion>({ url: `/artifacts/${encodeURIComponent(id)}`, params: { version } });
}

export function fetchArtifactVersions(id: string, before?: number) {
  return request<ArtifactSummary[]>({ url: `/artifacts/${encodeURIComponent(id)}/versions`, params: { before } });
}

export function createArtifact(data: ArtifactSaveRequest) {
  return request<ArtifactVersion>({ url: '/artifacts', method: 'post', data });
}

export function saveArtifactVersion(id: string, data: ArtifactSaveRequest) {
  return request<ArtifactVersion>({ url: `/artifacts/${encodeURIComponent(id)}/versions`, method: 'post', data });
}
