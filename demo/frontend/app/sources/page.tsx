"use client";

import { ChangeEvent, DragEvent, useEffect, useState } from "react";
import SiteHeader from "../SiteHeader";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

type SourceApi = {
  id:string;
  fileName:string;
  status:string;
  included:boolean;
  dateStart:string;
  dateEnd:string;
  rowCount:number;
  sheets:string[];
  validationMessage:string;
  usedByActiveModel:boolean;
  activeModelName:string | null;
  activeSince:string | null;
};

type TrainingRunApi = {
  id:string;
  status:string;
  selectedModel:string | null;
  message:string;
};

export default function SourcesPage() {
  const [fileName, setFileName] = useState("");
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [applying, setApplying] = useState(false);
  const [notice, setNotice] = useState("");
  const [applyNotice, setApplyNotice] = useState("");
  const [loaded, setLoaded] = useState(false);
  const [sources, setSources] = useState<SourceApi[]>([]);

  async function loadSources() {
    const response = await fetch(`${API_BASE}/api/data-sources`);
    if (!response.ok) throw new Error("Could not load sources");
    setSources(await response.json());
    setLoaded(true);
  }

  useEffect(() => {
    loadSources().catch((error) => { setLoaded(true); setNotice(error.message); });
  }, []);

  const displayedSources = sources.map((source) => ({
    ...source,
    detail:source.sheets.join(" + "),
    coverage:`${source.dateStart} – ${source.dateEnd}`,
    records:source.rowCount.toLocaleString(),
    state:source.usedByActiveModel ? `Used by ${source.activeModelName}` : source.status === "VALIDATED" ? "Validated" : "Rejected",
  }));
  const validatedCount = sources.filter((source) => source.included && source.status === "VALIDATED").length;
  const activeCount = sources.filter((source) => source.usedByActiveModel).length;

  function chooseFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (file) { setFileName(file.name); setSelectedFile(file); setNotice(""); }
  }

  function dropFile(event: DragEvent<HTMLLabelElement>) {
    event.preventDefault();
    setDragging(false);
    const file = event.dataTransfer.files?.[0];
    if (file) { setFileName(file.name); setSelectedFile(file); setNotice(""); }
  }

  async function uploadSource() {
    if (!selectedFile) return;
    setUploading(true);
    setNotice("");
    const form = new FormData();
    form.append("file", selectedFile);
    try {
      const response = await fetch(`${API_BASE}/api/data-sources`, { method:"POST", body:form });
      if (!response.ok) throw new Error((await response.json()).error ?? "Workbook validation failed");
      const source = await response.json();
      setSources((current) => [source, ...current.filter((item) => item.id !== source.id)]);
      setNotice(source.included ? "Workbook validated. Apply it to update the forecast." : source.validationMessage);
      setFileName("");
      setSelectedFile(null);
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Upload service is unavailable");
    } finally {
      setUploading(false);
    }
  }

  async function applyToForecast() {
    setApplying(true);
    setApplyNotice("Updating forecast model…");
    try {
      const response = await fetch(`${API_BASE}/api/training-runs`, { method:"POST" });
      if (!response.ok) throw new Error((await response.json()).error ?? "Model update could not start");
      const started: TrainingRunApi = await response.json();
      for (let attempt = 0; attempt < 60; attempt += 1) {
        await new Promise((resolve) => window.setTimeout(resolve, 700));
        const runsResponse = await fetch(`${API_BASE}/api/training-runs`);
        if (!runsResponse.ok) continue;
        const runs: TrainingRunApi[] = await runsResponse.json();
        const current = runs.find((run) => run.id === started.id);
        if (current?.status === "COMPLETED") {
          await loadSources();
          setApplyNotice(`Forecast now uses ${current.selectedModel}.`);
          return;
        }
        if (current?.status === "FAILED") throw new Error(current.message || "Model update failed");
      }
      setApplyNotice("Model update is still running. Refresh shortly.");
    } catch (error) {
      setApplyNotice(error instanceof Error ? error.message : "Model update is unavailable");
    } finally {
      setApplying(false);
    }
  }

  return (
    <main className="app-shell">
      <SiteHeader active="sources" status={`${activeCount} active`} />
      <div className="workspace sources-page">
        <section className="intro">
          <div><p>CHICKEN RICE DEMO</p><h1>Data sources</h1></div>
          <span>{validatedCount} validated</span>
        </section>

        <section className="sources-content">
          <div className="source-table">
            <div className="source-row source-head"><span>Type</span><span>Source</span><span>Coverage</span><span>Rows</span><span>Status</span></div>
            {displayedSources.map((source) => (
              <div className="source-row" key={source.id}><span className="file-type">XLS</span><div><strong>{source.fileName}</strong><span>{source.detail}</span></div><span>{source.coverage}</span><span>{source.records}</span><span className={source.usedByActiveModel ? "source-state active" : "source-state"} title={source.activeSince ? `Active since ${new Date(source.activeSince).toLocaleString("en-SG")}` : undefined}>{source.state}</span></div>
            ))}
            {loaded && displayedSources.length === 0 && <div className="empty-row">No data sources yet</div>}
          </div>

          <div className="apply-row">
            <div><strong>Forecast data</strong><span>{activeCount ? `${activeCount} source${activeCount === 1 ? "" : "s"} linked to the active model` : "No source is linked to the active model"}</span></div>
            <button className="primary-action" disabled={!validatedCount || applying} onClick={applyToForecast}>{applying ? "Applying…" : "Apply to forecast"}</button>
            {applyNotice && <span className="inline-notice" role="status">{applyNotice}</span>}
          </div>

          <div className="upload-area">
            <div><h2>Add a source</h2><span>Excel workbook · maximum 25 MB</span></div>
            <div className="upload-row">
              <label className={dragging ? "file-picker dragging" : "file-picker"} onDragOver={(event) => { event.preventDefault(); setDragging(true); }} onDragLeave={() => setDragging(false)} onDrop={dropFile}>
                <input type="file" accept=".xlsx" onChange={chooseFile} />
                <span>{fileName || "Choose or drop an Excel workbook"}</span>
              </label>
              {fileName && <button className="text-action" onClick={() => { setFileName(""); setSelectedFile(null); }}>Remove</button>}
              <button className="primary-action" disabled={!fileName || uploading} onClick={uploadSource}>{uploading ? "Validating…" : "Add source"}</button>
            </div>
            {notice && <span className="inline-notice" role="status">{notice}</span>}
          </div>
        </section>

        <footer><span>Chicken rice demo</span><span>PostgreSQL in Docker · H2 for local development</span></footer>
      </div>
    </main>
  );
}
