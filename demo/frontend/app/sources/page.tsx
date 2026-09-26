"use client";

import { ChangeEvent, DragEvent, useEffect, useState } from "react";
import SiteHeader from "../SiteHeader";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

const sampleSources = [
  { name: "hawker_sales.xlsx", detail: "Daily Summary + Daily Item Sales", coverage: "1 Mar – 31 Aug 2026", records: "1,734", state: "In use", active: true },
  { name: "restaurant.csv", detail: "Historical benchmark dataset", coverage: "4 Oct 2013 – 7 Nov 2015", records: "5,320", state: "Reference", active: false },
];

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
};

export default function SourcesPage() {
  const [fileName, setFileName] = useState("");
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [notice, setNotice] = useState("");
  const [liveSources, setLiveSources] = useState<SourceApi[]>([]);

  useEffect(() => {
    fetch(`${API_BASE}/api/data-sources`)
      .then((response) => response.ok ? response.json() : [])
      .then(setLiveSources)
      .catch(() => undefined);
  }, []);

  const displayedSources = liveSources.length ? liveSources.map((source) => ({
    name:source.fileName,
    detail:source.sheets.join(" + "),
    coverage:`${source.dateStart} – ${source.dateEnd}`,
    records:source.rowCount.toLocaleString(),
    state:source.included ? "In use" : source.status,
    active:source.included,
  })) : sampleSources;

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
      setLiveSources((current) => [source, ...current.filter((item) => item.id !== source.id)]);
      setNotice(source.validationMessage);
      setFileName("");
      setSelectedFile(null);
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Upload service is unavailable");
    } finally {
      setUploading(false);
    }
  }

  return (
    <main className="app-shell">
      <SiteHeader active="sources" status={`${displayedSources.length} sources`} />
      <div className="workspace sources-page">
        <section className="intro">
          <div><p>CHICKEN RICE DEMO</p><h1>Data sources</h1></div>
          <span>{displayedSources.length} connected</span>
        </section>

        <section className="sources-content">
          <div className="source-table">
            <div className="source-row source-head"><span>Type</span><span>Source</span><span>Coverage</span><span>Rows</span><span>Status</span></div>
            {displayedSources.map((source) => (
              <div className="source-row" key={source.name}><span className="file-type">XLS</span><div><strong>{source.name}</strong><span>{source.detail}</span></div><span>{source.coverage}</span><span>{source.records}</span><span className={source.active ? "source-state active" : "source-state"}>{source.state}</span></div>
            ))}
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

        <footer><span>Chicken rice demo</span><span>Data used by the active forecast</span></footer>
      </div>
    </main>
  );
}
