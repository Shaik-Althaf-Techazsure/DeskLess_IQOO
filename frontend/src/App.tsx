import { Navigate, Route, Routes } from "react-router-dom";
import Capture from "./pages/Capture";
import Home from "./pages/Home";
import ItemPage from "./pages/ItemPage";
import Report from "./pages/Report";
import Walkthrough from "./pages/Walkthrough";

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/w/:id" element={<Walkthrough />} />
      <Route path="/w/:id/add" element={<Capture />} />
      <Route path="/w/:id/item/:itemId" element={<ItemPage />} />
      <Route path="/w/:id/report" element={<Report />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
