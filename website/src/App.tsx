import { Navbar } from './components/Navbar.tsx'
import { Footer } from './components/Footer.tsx'
import { HeroSection } from './sections/HeroSection.tsx'
import { ProblemSection } from './sections/ProblemSection.tsx'
import { PrinciplesSection } from './sections/PrinciplesSection.tsx'
import { CapabilitiesSection } from './sections/CapabilitiesSection.tsx'
import { ArchitectureSection } from './sections/ArchitectureSection.tsx'
import { UseCasesSection } from './sections/UseCasesSection.tsx'
import { ResourcesSection } from './sections/ResourcesSection.tsx'

function App() {
  return (
    <>
      <Navbar />
      <main className="flex-1">
        <HeroSection />
        <ProblemSection />
        <PrinciplesSection />
        <CapabilitiesSection />
        <ArchitectureSection />
        <UseCasesSection />
        <ResourcesSection />
      </main>
      <Footer />
    </>
  )
}

export default App
