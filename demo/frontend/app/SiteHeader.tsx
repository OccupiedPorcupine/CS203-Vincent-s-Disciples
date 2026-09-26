type SiteHeaderProps = {
  active: "forecast" | "sources";
  status?: string;
};

export default function SiteHeader({ active, status = "Model ready" }: SiteHeaderProps) {
  return (
    <header className="site-header">
      <a className="brand" href="/" aria-label="Chicken rice demo home"><span>CR</span><strong>Chicken rice demo</strong></a>
      <nav aria-label="Primary navigation">
        <a className={active === "forecast" ? "active" : ""} href="/">Forecast</a>
        <a className={active === "sources" ? "active" : ""} href="/sources">Sources</a>
      </nav>
      <div className="header-status"><i />{status}</div>
    </header>
  );
}
